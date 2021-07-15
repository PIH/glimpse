package org.pih.petl.job.type;

import com.microsoft.sqlserver.jdbc.ISQLServerConnection;
import com.microsoft.sqlserver.jdbc.SQLServerBulkCopy;
import com.microsoft.sqlserver.jdbc.SQLServerBulkCopyOptions;
import org.apache.commons.dbutils.DbUtils;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pih.petl.ApplicationConfig;
import org.pih.petl.PetlException;
import org.pih.petl.api.ExecutionContext;
import org.pih.petl.job.PetlJob;
import org.pih.petl.job.config.ConfigFile;
import org.pih.petl.job.config.PetlJobConfig;
import org.pih.petl.job.datasource.DatabaseUtil;
import org.pih.petl.job.datasource.EtlDataSource;
import org.pih.petl.job.datasource.SqlStatementParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.List;

/**
 * PetlJob that can load into SQL Server table
 */
public class SqlServerImportJob implements PetlJob {

    private static Log log = LogFactory.getLog(SqlServerImportJob.class);

    private Connection sourceConnection = null;
    private Connection targetConnection = null;
    private EtlDataSource sourceDatasource = null;
    private EtlDataSource targetDatasource = null;

    /**
     * Creates a new instance of the job
     */
    public SqlServerImportJob() {
    }

    /**
     * @see PetlJob
     */
    @Override
    public void execute(final ExecutionContext context) throws Exception {

        ApplicationConfig appConfig = context.getApplicationConfig();
        PetlJobConfig config = context.getJobConfig();

        context.setStatus("Loading configuration");

        // Get source datasource
        String sourceDataSourceFilename = config.getString("extract", "datasource");
        sourceDatasource = appConfig.getEtlDataSource(sourceDataSourceFilename);

        // Get source context statements
        String sourceContextStatements = "";
        String sourceContextFileName = config.getString("extract", "context");
        if (sourceContextFileName != null) {
            ConfigFile sourceContextFile = appConfig.getConfigFile(sourceContextFileName);
            sourceContextStatements = sourceContextFile.getContents();
        }

        // Get source query
        String sourceQueryFileName = config.getString("extract", "query");
        ConfigFile sourceQueryFile = appConfig.getConfigFile(sourceQueryFileName);
        String sourceQuery = sourceContextStatements + sourceQueryFile.getContents();

        // Get any conditional
        String conditional = config.getString("conditional");

        // Get target datasource
        String targetDataFileName = config.getString("load", "datasource");
        targetDatasource = appConfig.getEtlDataSource(targetDataFileName);

        // Get target table name
        String targetTable = config.getString("load", "table");

        // Get target table schema
        String targetSchemaFilename = config.getString("load", "schema");
        ConfigFile targetSchemaFile = appConfig.getConfigFile(targetSchemaFilename);
        String targetSchema = targetSchemaFile.getContents();

        // TODO: Add validation in

        // execute conditional, if present, and skip job if conditional returns false
        if (StringUtils.isNotEmpty(conditional)) {
            if (!testConditional(conditional)) {
                context.setStatus("Conditional returned false, skipping");
                log.info("Conditional returned false, skipping this job");
                return;
            }
        }

        try {
            QueryRunner qr = new QueryRunner();
            sourceConnection = DatabaseUtil.openConnection(sourceDatasource);
            targetConnection = DatabaseUtil.openConnection(targetDatasource);

            boolean originalSourceAutoCommit = sourceConnection.getAutoCommit();
            boolean originalTargetAutocommit = targetConnection.getAutoCommit();

            RowCountUpdater updater = new RowCountUpdater(targetConnection, context, targetTable);
            try {
                updater.start();
                sourceConnection.setAutoCommit(false); // We intend to rollback changes to source after querying DB
                targetConnection.setAutoCommit(true);  // We want to commit to target as we go, to query status

                if (config.getBoolean(true,"dropAndRecreateTable")) {
                    // drop existing target table  (we don't use "drop table if exists..." syntax for backwards compatibility with earlier versions of SQL Server
                    context.setStatus("Dropping existing table");
                    qr.update(targetConnection, "IF OBJECT_ID('dbo." + targetTable + "') IS NOT NULL DROP TABLE dbo." + targetTable);
                }

                // Then, create the target table if necessary
                context.setStatus("Creating table");
                qr.update(targetConnection, "IF OBJECT_ID('dbo." + targetTable+ "') IS NULL " + targetSchema);

                // Now execute a bulk import
                context.setStatus("Executing import");

                // Parse the source query into statements
                List<String> stmts = SqlStatementParser.parseSqlIntoStatements(sourceQuery, ";");
                log.debug("Parsed extract query into " + stmts.size() + " statements");

                // Iterate over each statement, and execute.  The final statement is expected to select the data out.
                for (Iterator<String> sqlIterator = stmts.iterator(); sqlIterator.hasNext();) {
                    String sqlStatement = sqlIterator.next();
                    Statement statement = null;
                    try {
                        log.debug("Executing: " + sqlStatement);
                        StopWatch sw = new StopWatch();
                        sw.start();
                        if (sqlIterator.hasNext()) {
                            statement = sourceConnection.createStatement();
                            statement.execute(sqlStatement);
                            log.debug("Statement executed");
                        }
                        else {
                            log.debug("This is the last statement, treat it as the extraction query");
                            statement = sourceConnection.prepareStatement(
                                    sqlStatement, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY
                            );
                            ResultSet resultSet = null;
                            try {
                                resultSet = ((PreparedStatement)statement).executeQuery();
                                if (resultSet != null) {
                                    // Skip to the end to get the number of rows that ResultSet contains
                                    resultSet.last();
                                    Integer rowCount = resultSet.getRow();
                                    context.setTotalExpected(rowCount);

                                    // Reset back to the beginning to ensure all rows are extracted
                                    resultSet.beforeFirst();

                                    // Pass the ResultSet to bulk copy to SQL Server (TODO: Handle other DBs)
                                    Connection sqlServerConnection = getAsSqlServerConnection(targetConnection);
                                    SQLServerBulkCopy bulkCopy = new SQLServerBulkCopy(sqlServerConnection);
                                    SQLServerBulkCopyOptions bco = new SQLServerBulkCopyOptions();
                                    bco.setKeepIdentity(true);
                                    bco.setBatchSize(100);
                                    bco.setBulkCopyTimeout(3600);
                                    bulkCopy.setBulkCopyOptions(bco);
                                    bulkCopy.setDestinationTableName(targetTable);
                                    bulkCopy.writeToServer(resultSet);
                                }
                                else {
                                    throw new PetlException("Invalid SQL extraction, no result set found");
                                }
                            }
                            finally {
                                DbUtils.closeQuietly(resultSet);
                            }
                        }
                        sw.stop();
                        log.debug("Statement executed in: " + sw.toString());
                    }
                    finally {
                        DbUtils.closeQuietly(statement);
                    }
                }

                // Update the status at the end of the bulk copy
                Integer rowCount = DatabaseUtil.rowCount(targetConnection, targetTable);
                context.setTotalLoaded(rowCount);
                context.setStatus("Import Completed Sucessfully");
            }
            finally {
                updater.cancel();
                sourceConnection.rollback();
                sourceConnection.setAutoCommit(originalSourceAutoCommit);
                targetConnection.setAutoCommit(originalTargetAutocommit);
            }
        }
        finally {
            DbUtils.closeQuietly(targetConnection);
            DbUtils.closeQuietly(sourceConnection);
        }
    }

    /**
     * @return a connection for the given connection.  This allows mocking to occur in unit tests as needed
     */
    public Connection getAsSqlServerConnection(Connection connection) throws SQLException {
        if (connection.isWrapperFor(ISQLServerConnection.class)) {
            if (!(connection instanceof ISQLServerConnection)) {
                log.warn("The passed connection is a wrapper for ISQLServerConnection, unwrapping it.");
                return connection.unwrap(ISQLServerConnection.class);
            }
        }
        return connection;
    }

    private Boolean testConditional(String conditional) throws SQLException {

        Boolean result = true;

        try {
            sourceConnection = DatabaseUtil.openConnection(sourceDatasource);
            boolean originalSourceAutoCommit = sourceConnection.getAutoCommit();
            try {
                sourceConnection.setAutoCommit(false); // We intend to rollback changes to source after querying DB
                Statement statement = sourceConnection.createStatement();
                statement.execute(conditional);
                ResultSet resultSet = statement.getResultSet();
                resultSet.next();
                result = resultSet.getBoolean(1);
            }
            finally {
                sourceConnection.rollback();
                sourceConnection.setAutoCommit(originalSourceAutoCommit);
            }
        }
        finally {
            DbUtils.closeQuietly(sourceConnection);
        }

        return result;
    }

    /**
     * Inner class allows for checking the target for the number or rows currently loaded, to update status over time
     */
    class RowCountUpdater extends Thread {

        private long lastExecutionTime = System.currentTimeMillis();
        private long msBetweenExecutions = 1000*5;

        private Connection connection;
        private ExecutionContext context;
        private String table;

        public RowCountUpdater(Connection connection, ExecutionContext context, String table) {
            this.connection = connection;
            this.context = context;
            this.table = table;
        }

        @Override
        public void run() {
            while (connection != null) {
                long msSinceLast = System.currentTimeMillis() - lastExecutionTime;
                if (msSinceLast >= msBetweenExecutions) {
                    try {
                        Integer rowCount = DatabaseUtil.rowCount(connection, table);
                        context.setTotalLoaded(rowCount);
                    }
                    catch (Exception e) {}
                    lastExecutionTime = System.currentTimeMillis();
                }
            }
        }

        public void cancel() {
            connection = null;
        }
    }
}
