package net.ehicks.eoi;

import com.zaxxer.hikari.HikariDataSource;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class EOI
{
    private static final Logger log = LoggerFactory.getLogger(EOI.class);

    private static HikariDataSource cp;
    private static Server server;
    public static String databaseBrand = "";
    public static boolean enableCache = false;
    public static ThreadLocal<Connection> conn = new ThreadLocal<>();

    // example connectionString: jdbc:h2:~/test;TRACE_LEVEL_FILE=1;CACHE_SIZE=131072;SCHEMA=CINEMANG
    public static void init(String connectionString)
    {
        try
        {
            if (connectionString.contains("jdbc:h2"))
            {
                databaseBrand = "h2";
                server = Server.createTcpServer("-tcpAllowOthers").start();
            }
            if (connectionString.contains("jdbc:sqlserver"))
                databaseBrand = "sqlserver";
            log.info("EOI is connecting to {} db", databaseBrand);

            cp = new HikariDataSource();
            if (connectionString.contains("jdbc:sqlserver"))
                cp.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            cp.setJdbcUrl(connectionString);
        }
        catch (Exception e)
        {
            log.error(e.getMessage(), e);
        }
    }

    public static void destroy()
    {
        cp.close();
        if (databaseBrand.equals("h2"))
            server.stop();
    }

    private static Connection getConnection()
    {
        return getConnection(true);
    }

    private static Connection getConnection(boolean autoCommit)
    {
        Connection connection = conn.get();
        try
        {
            // check for existing connection (indicates a transaction was already started??)
            if (connection == null)
            {
                connection = cp.getConnection();
                conn.set(connection);

                if (!autoCommit)
                    connection.setAutoCommit(false);
            }

            return connection;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return null;
    }

    public static void startTransaction()
    {
        getConnection(false);
    }

    public static void commit()
    {
        try
        {
            Connection connection = conn.get();
            if (connection != null)
            {
                connection.commit();
                closeConnection(true);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void rollback()
    {
        try
        {
            Connection connection = conn.get();
            if (connection != null)
            {
                if (!connection.getAutoCommit())
                    connection.rollback();
                closeConnection(true);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static void closeConnection(boolean hardClose)
    {
        try
        {
            Connection connection = conn.get();
            if (connection != null)
            {
                if (!connection.getAutoCommit() && !hardClose)
                    return;

                connection.setAutoCommit(true);
                connection.close();
                conn.remove();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    // -------- String-Based Methods -------- //

    // for INSERT, UPDATE, or DELETE, or DDL statements
    public static int executeUpdate(String queryString)
    {
        Connection connection = getConnection();
        try
        {
            try (Statement statement = connection.createStatement();)
            {
                return statement.executeUpdate(queryString);
            }
            catch (Exception e)
            {
                rollback();
                e.printStackTrace();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            closeConnection(false);
        }

        return 0;
    }

    // for INSERT, UPDATE, or DELETE, or DDL statements
    public static int executePreparedUpdate(String queryString, List<Object> args)
    {
        Connection connection = getConnection();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString);)
        {
            int argIndex = 1;
            for (Object arg : args)
                setPreparedStatementParameter(preparedStatement, argIndex++, arg);

            return preparedStatement.executeUpdate();
        }
        catch (Exception e)
        {
            rollback();
            e.printStackTrace();
        }
        finally
        {
            closeConnection(false);
        }

        return 0;
    }

    public static void execute(String queryString)
    {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement();)
        {
            statement.execute(queryString);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    // -------- Object-Based Methods -------- //

    public static long insert(Object object)
    {
        if (object instanceof List)
            return _insertFromList((List) object);
        else
            return _insert(object);
    }

    private static long _insertFromList(List<?> objects)
    {
        int success = 0;
        int fail = 0;
        for (Object object : objects)
        {
            long result = _insert(object);
            if (result > 0)
                success++;
            else
                fail++;
        }
        log.info("Finished mass create: {} succeeded, {} failed", success, fail);
        return success;
    }

    private static long _insert(Object object)
    {
        String insertStatement = SQLGenerator.getInsertStatement(object);

        Connection connection = getConnection();
        try (PreparedStatement preparedStatement = connection.prepareStatement(insertStatement, Statement.RETURN_GENERATED_KEYS);)
        {
            DBMap dbMap = DBMap.getDBMapByClass(object.getClass());

            int argIndex = 1;
            for (DBMapField dbMapField : dbMap.fields)
            {
                if (dbMapField.autoIncrement)
                    continue;

                Object value = dbMapField.getValue(object);
                if (value == null)
                {
                    preparedStatement.setNull(argIndex++, Types.NULL);
                    continue;
                }

                setPreparedStatementParameter(preparedStatement, argIndex++, value);
            }

            int queryResult = preparedStatement.executeUpdate();

            ResultSet generatedKeysResultSet = preparedStatement.getGeneratedKeys();
            if (generatedKeysResultSet == null || !generatedKeysResultSet.next())
                return 0;

            long generatedKey = generatedKeysResultSet.getLong(1);

            // prepare audit
            createAudit("INSERT", dbMap, generatedKey);

            return generatedKey;
        }
        catch (Exception e)
        {
            rollback();
            e.printStackTrace();
        }
        finally
        {
            closeConnection(false);
        }
        return 0;
    }

    public static long batchInsert(List<?> objects)
    {
        String insertStatement = SQLGenerator.getInsertStatement(objects.get(0));

        Connection connection = getConnection();
        try (PreparedStatement preparedStatement = connection.prepareStatement(insertStatement))
        {
            DBMap dbMap = DBMap.getDBMapByClass(objects.get(0).getClass());

            for (Object object : objects)
            {
                int argIndex = 1;
                for (DBMapField dbMapField : dbMap.fields)
                {
                    if (dbMapField.autoIncrement)
                        continue;

                    Object value = dbMapField.getValue(object);

                    setPreparedStatementParameter(preparedStatement, argIndex++, value);
                }

                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        }
        catch (Exception e)
        {
            rollback();
            e.printStackTrace();
        }
        finally
        {
            closeConnection(false);
        }
        return 0;
    }

    private static void createAudit(String eventType, DBMap dbMap, long objectId)
    {
        createAudit(eventType, dbMap, objectId, null, null, null, null);
    }

    private static void createAudit(String eventType, DBMap dbMap, long objectId, Object object, String fieldName, String oldValue, String newValue)
    {
        if (!dbMap.className.equals("Audit"))
        {
            String objectKey = "";
            if (object != null)
                objectKey = object.toString();
            else
                if (objectId != 0)
                    objectKey = dbMap.className + ":" + objectId;

            String queryString = "insert into audits (object_key, event_time, event_type, field_name, old_value, new_value) values (?,?,?,?,?,?);";
            List<Object> args = Arrays.asList(objectKey, new Date(), eventType, fieldName, oldValue, newValue);
            EOI.executePreparedUpdate(queryString, args);
        }
    }

    public static void update(Object object)
    {
        if (object instanceof List)
            _updateFromList((List) object);
        else
            _update(object);
    }

    private static void _updateFromList(List<?> objects)
    {
        int success = 0;
        int fail = 0;
        for (Object object : objects)
        {
            int result = _update(object);
            if (result == 1)
                success++;
            else
                fail++;
        }
        log.info("Finished mass update: {} succeeded, {} failed", success, fail);
    }

    private static int _update(Object object)
    {
        PSIngredients psIngredients = SQLGenerator.getUpdateStatement(object);
        if (psIngredients == null)
            return 0;

        Connection connection = getConnection();
        try (PreparedStatement preparedStatement = connection.prepareStatement(psIngredients.query);)
        {
            int argIndex = 1;
            for (Object arg : psIngredients.args)
                setPreparedStatementParameter(preparedStatement, argIndex++, arg);

            int result = preparedStatement.executeUpdate();
            if (result == 1)
            {
                DBMap dbMap = DBMap.getDBMapByClass(object.getClass());
                for (PSIngredients.UpdatedField updatedField : psIngredients.updatedFields)
                {
                    String oldValue = updatedField.oldValue == null ? "<NULL>" : updatedField.oldValue.toString();
                    String newValue = updatedField.newValue == null ? "<NULL>" : updatedField.newValue.toString();
                    createAudit("UPDATE", dbMap, 0, object, updatedField.fieldName, oldValue, newValue);
                }

                if (enableCache)
                    EOICache.set(object);
                return result;
            }
        }
        catch (Exception e)
        {
            rollback();
            e.printStackTrace();
        }
        finally
        {
            closeConnection(false);
        }

        return 0;
    }

    public static <T> T executeQueryOneResult(String queryString)
    {
        return executeQueryOneResult(queryString, new ArrayList<>(), false);
    }

    public static <T> T executeQueryOneResult(String queryString, List<Object> args)
    {
        return executeQueryOneResult(queryString, args, false);
    }

    public static <T> T executeQueryOneResult(String queryString, List<Object> args, boolean bypassCache)
    {
        List<T> results = executeQuery(queryString, args, bypassCache);
        if (results != null && results.size() > 0)
            return results.get(0);
        return null;
    }

    public static <T> List<T> executeQuery(String queryString)
    {
        return executeQuery(queryString, new ArrayList<>(), false);
    }

    public static <T> List<T> executeQuery(String queryString, List<Object> args)
    {
        return executeQuery(queryString, args, false);
    }

    public static <T> List<T> executeQuery(String queryString, List<Object> args, boolean bypassCache)
    {
        Connection connection = getConnection();
        try (PreparedStatement preparedStatement = connection.prepareStatement(queryString))
        {
            int argIndex = 1;
            for (Object arg : args)
                setPreparedStatementParameter(preparedStatement, argIndex++, arg);

            long start = System.currentTimeMillis();
            ResultSet resultSet = preparedStatement.executeQuery();
            long end = System.currentTimeMillis();
            if (end - start >= 100)
            {
                String message = "EOI QUERY took {} ms: {}. Args: {}";
                log.info(message, (end - start), queryString, args);
            }

            return ResultSetParser.parseResultSet(queryString, resultSet, bypassCache);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            closeConnection(false);
        }

        return null;
    }

    public static <T> List<T> executeQueryWithoutPS(String queryString, boolean bypassCache)
    {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(queryString);)
        {
            return ResultSetParser.parseResultSet(queryString, resultSet, bypassCache);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    public static int executeDelete(Object object)
    {
        PSIngredients psIngredients = SQLGenerator.getDeleteStatement(object);
        if (psIngredients == null)
            return 0;

        try (Connection connection = getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(psIngredients.query);)
        {
            int argIndex = 1;
            for (Object arg : psIngredients.args)
                setPreparedStatementParameter(preparedStatement, argIndex++, arg);

            int result = preparedStatement.executeUpdate();
            if (result == 1)
            {
                // prepare audit
                DBMap dbMap = DBMap.getDBMapByClass(object.getClass());
                createAudit("DELETE", dbMap, (Long) dbMap.getPKFields().get(0).getGetter().invoke(object));

                EOICache.unset(object);
                return result;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return 0;
    }

    private static void setPreparedStatementParameter(PreparedStatement ps, int argIndex, Object obj) throws SQLException
    {
        if (obj instanceof String) ps.setString(argIndex, (String) obj);
        if (obj instanceof Integer) ps.setInt(argIndex, (Integer) obj);
        if (obj instanceof Long) ps.setLong(argIndex, (Long) obj);
        if (obj instanceof BigDecimal) ps.setBigDecimal(argIndex, (BigDecimal) obj);
        if (obj instanceof Date) ps.setTimestamp(argIndex, new Timestamp(((Date) obj).getTime()));
        if (obj instanceof byte[]) ps.setBytes(argIndex, (byte[]) obj);
        if (obj instanceof Boolean) ps.setBoolean(argIndex, (Boolean) obj);
        if (obj == null) ps.setNull(argIndex, Types.NULL);
    }

    public static boolean isTableExists(DBMap dbMap)
    {
        Connection connection = getConnection();
        try
        {
            DatabaseMetaData databaseMetaData = connection.getMetaData();
            try (ResultSet resultSet = databaseMetaData.getTables(connection.getCatalog(), null, null, null);)
            {
                List<String> tableNamesFromDb = new ArrayList<>();
                while (resultSet.next())
                {
                    String tableName = resultSet.getString("TABLE_NAME").toUpperCase();
                    tableNamesFromDb.add(tableName);
                    if (tableName.equals(dbMap.tableName.toUpperCase()))
                        return true;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            closeConnection(false);
        }
        return false;
    }

    public static List<String> getCPInfo()
    {
        List<String> cpInfo = new ArrayList<>();
//        cpInfo.add("Active Connections: " + cp.getActiveConnections());
//        cpInfo.add("Max Connections: " + cp.getMaxConnections());
        return cpInfo;
    }
}