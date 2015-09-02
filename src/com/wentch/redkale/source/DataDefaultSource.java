/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.wentch.redkale.source;

import static com.wentch.redkale.source.FilterNode.*;
import com.wentch.redkale.util.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;
import javax.annotation.*;
import javax.sql.*;
import javax.xml.stream.*;

/**
 *
 * @author zhangjx
 */
@SuppressWarnings("unchecked")
public final class DataDefaultSource implements DataSource, Nameable {

    public static final String DATASOURCE_CONFPATH = "DATASOURCE_CONFPATH";

    static final String JDBC_CONNECTIONMAX = "javax.persistence.connection.limit";

    static final String JDBC_URL = "javax.persistence.jdbc.url";

    static final String JDBC_USER = "javax.persistence.jdbc.user";

    static final String JDBC_PWD = "javax.persistence.jdbc.password";

    static final String JDBC_DRIVER = "javax.persistence.jdbc.driver";

    static final String JDBC_SOURCE = "javax.persistence.jdbc.source";

    private static final Flipper FLIPPER_ONE = new Flipper(1);

    final Logger logger = Logger.getLogger(DataDefaultSource.class.getSimpleName());

    final AtomicBoolean debug = new AtomicBoolean(logger.isLoggable(Level.FINEST));

    final String name;

    final URL conf;

    final boolean cacheForbidden;

    private final JDBCPoolSource readPool;

    private final JDBCPoolSource writePool;

    @Resource(name = "property.datasource.nodeid")
    private int nodeid;

    @Resource(name = "$")
    private DataSQLListener writeListener;

    @Resource(name = "$")
    private DataCacheListener cacheListener;

    private static class DataJDBCConnection extends DataConnection {

        private final Connection sqlconn;

        private DataJDBCConnection(Connection c) {
            super(c);
            this.sqlconn = c;
            try {
                this.sqlconn.setAutoCommit(true);
            } catch (Exception e) {
                //do nothing
            }
        }

        @Override
        public void close() {
            try {
                sqlconn.close();
            } catch (Exception e) {
                //do nothing
            }
        }

        @Override
        public boolean commit() {
            try {
                sqlconn.commit();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void rollback() {
            try {
                sqlconn.rollback();
            } catch (Exception e) {
                //do nothing
            }
        }
    }

    private final Function<Class, List> fullloader = (t) -> queryList(t, (FilterNode) null);

    public DataDefaultSource() throws IOException {
        this("");
    }

    public DataDefaultSource(final String unitName) throws IOException {
        this(unitName, System.getProperty(DATASOURCE_CONFPATH) == null
                ? DataDefaultSource.class.getResource("/META-INF/persistence.xml")
                : new File(System.getProperty(DATASOURCE_CONFPATH)).toURI().toURL());
    }

    public DataDefaultSource(final String unitName, URL url) throws IOException {
        if (url == null) url = this.getClass().getResource("/persistence.xml");
        InputStream in = url.openStream();
        Map<String, Properties> map = loadProperties(in);
        Properties readprop = null;
        Properties writeprop = null;
        if (unitName != null) {
            readprop = map.get(unitName);
            writeprop = readprop;
            if (readprop == null) {
                readprop = map.get(unitName + ".read");
                writeprop = map.get(unitName + ".write");
            }
        }
        if ((unitName == null || unitName.isEmpty()) || readprop == null) {
            String key = null;
            for (Map.Entry<String, Properties> en : map.entrySet()) {
                key = en.getKey();
                readprop = en.getValue();
                writeprop = readprop;
                break;
            }
            if (key != null && (key.endsWith(".read") || key.endsWith(".write"))) {
                if (key.endsWith(".read")) {
                    writeprop = map.get(key.substring(0, key.lastIndexOf('.')) + ".write");
                } else {
                    readprop = map.get(key.substring(0, key.lastIndexOf('.')) + ".read");
                }
            }
        }
        if (readprop == null) throw new RuntimeException("not found persistence properties (unit:" + unitName + ")");
        this.name = unitName;
        this.conf = url;
        this.readPool = new JDBCPoolSource(this, "read", readprop);
        this.writePool = new JDBCPoolSource(this, "write", writeprop);
        this.cacheForbidden = "NONE".equalsIgnoreCase(readprop.getProperty("shared-cache-mode"));
    }

    public DataDefaultSource(String unitName, Properties readprop, Properties writeprop) {
        this.name = unitName;
        this.conf = null;
        this.readPool = new JDBCPoolSource(this, "read", readprop);
        this.writePool = new JDBCPoolSource(this, "write", writeprop);
        this.cacheForbidden = "NONE".equalsIgnoreCase(readprop.getProperty("shared-cache-mode"));
    }

    public static Map<String, DataDefaultSource> create(final InputStream in) {
        Map<String, Properties> map = loadProperties(in);
        Map<String, Properties[]> maps = new HashMap<>();
        map.entrySet().stream().forEach((en) -> {
            if (en.getKey().endsWith(".read") || en.getKey().endsWith(".write")) {
                String key = en.getKey().substring(0, en.getKey().lastIndexOf('.'));
                if (maps.containsKey(key)) return;
                boolean read = en.getKey().endsWith(".read");
                Properties rp = read ? en.getValue() : map.get(key + ".read");
                Properties wp = read ? map.get(key + ".write") : en.getValue();
                maps.put(key, new Properties[]{rp, wp});
            } else {
                maps.put(en.getKey(), new Properties[]{en.getValue(), en.getValue()});
            }
        });
        Map<String, DataDefaultSource> result = new HashMap<>();
        maps.entrySet().stream().forEach((en) -> {
            result.put(en.getKey(), new DataDefaultSource(en.getKey(), en.getValue()[0], en.getValue()[1]));
        });
        return result;
    }

    static Map<String, Properties> loadProperties(final InputStream in0) {
        final Map<String, Properties> map = new LinkedHashMap();
        Properties result = new Properties();
        boolean flag = false;
        try (final InputStream in = in0) {
            XMLStreamReader reader = XMLInputFactory.newFactory().createXMLStreamReader(in);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("persistence-unit".equalsIgnoreCase(reader.getLocalName())) {
                        if (!result.isEmpty()) result = new Properties();
                        map.put(reader.getAttributeValue(null, "name"), result);
                        flag = true;
                    } else if (flag && "property".equalsIgnoreCase(reader.getLocalName())) {
                        String name = reader.getAttributeValue(null, "name");
                        String value = reader.getAttributeValue(null, "value");
                        if (name == null) continue;
                        result.put(name, value);
                    } else if (flag && "shared-cache-mode".equalsIgnoreCase(reader.getLocalName())) {
                        result.put(reader.getLocalName(), reader.getElementText());
                    }
                }
            }
            in.close();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return map;
    }

    static ConnectionPoolDataSource createDataSource(Properties property) {
        try {
            return createDataSource(property.getProperty(JDBC_SOURCE, property.getProperty(JDBC_DRIVER)),
                    property.getProperty(JDBC_URL), property.getProperty(JDBC_USER), property.getProperty(JDBC_PWD));
        } catch (Exception ex) {
            throw new RuntimeException("(" + property + ") have no jdbc parameters", ex);
        }
    }

    static ConnectionPoolDataSource createDataSource(final String source0, String url, String user, String password) throws Exception {
        String source = source0;
        if (source0.contains("Driver")) {  //为了兼容JPA的配置文件
            switch (source0) {
                case "org.mariadb.jdbc.Driver":
                    source = "org.mariadb.jdbc.MySQLDataSource";
                    break;
                case "com.mysql.jdbc.Driver":
                    source = "com.mysql.jdbc.jdbc2.optional.MysqlConnectionPoolDataSource";
                    break;
                case "oracle.jdbc.driver.OracleDriver":
                    source = "oracle.jdbc.pool.OracleConnectionPoolDataSource";
                    break;
                case "com.microsoft.sqlserver.jdbc.SQLServerDriver":
                    source = "com.microsoft.sqlserver.jdbc.SQLServerConnectionPoolDataSource";
                    break;
            }
        }
        final Class clazz = Class.forName(source);
        Object pdsource = clazz.newInstance();
        Method seturlm;
        try {
            seturlm = clazz.getMethod("setUrl", String.class);
        } catch (Exception e) {
            seturlm = clazz.getMethod("setURL", String.class);
        }
        seturlm.invoke(pdsource, url);
        clazz.getMethod("setUser", String.class).invoke(pdsource, user);
        clazz.getMethod("setPassword", String.class).invoke(pdsource, password);
        return (ConnectionPoolDataSource) pdsource;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public DataConnection createReadConnection() {
        return new DataJDBCConnection(createReadSQLConnection());
    }

    @Override
    public DataConnection createWriteConnection() {
        return new DataJDBCConnection(createWriteSQLConnection());
    }

    public void close() {
        readPool.close();
        writePool.close();
    }

    public String getName() {
        return name;
    }

    private Connection createReadSQLConnection() {
        return readPool.poll();
    }

    private <T> Connection createWriteSQLConnection() {
        return writePool.poll();
    }

    private void closeSQLConnection(final Connection sqlconn) {
        if (sqlconn == null) return;
        try {
            sqlconn.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "closeSQLConnection abort", e);
        }
    }

    public <T> void execute(String... sqls) {
        Connection conn = createWriteSQLConnection();
        try {
            execute(conn, sqls);
        } finally {
            closeSQLConnection(conn);
        }
    }

    public <T> void execute(final DataConnection conn, String... sqls) {
        execute((Connection) conn.getConnection(), sqls);
    }

    private <T> void execute(final Connection conn, String... sqls) {
        if (sqls.length == 0) return;
        try {
            final Statement stmt = conn.createStatement();
            for (String sql : sqls) {
                stmt.execute(sql);
            }
            stmt.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> EntityInfo<T> loadEntityInfo(Class<T> clazz) {
        return EntityInfo.load(clazz, this.nodeid, this.cacheForbidden, fullloader);
    }

    private <T extends FilterBean> FilterBeanNode loadFilterBeanNode(Class<T> clazz) {
        return FilterBeanNode.load(clazz, this.nodeid, this.cacheForbidden, fullloader);
    }

    /**
     * 将entity的对象全部加载到Cache中去，如果clazz没有被@javax.persistence.Cacheable注解则不做任何事
     * <p>
     * @param <T>
     * @param clazz
     */
    @Override
    public <T> void refreshCache(Class<T> clazz) {
        EntityInfo<T> info = loadEntityInfo(clazz);
        EntityCache<T> cache = info.getCache();
        if (cache == null) return;
        cache.fullLoad(queryList(clazz, (FilterNode) null));
    }

    //----------------------insertCache-----------------------------
    /**
     * 新增对象， 必须是Entity对象
     *
     * @param <T>
     * @param values
     */
    @Override
    public <T> void insert(T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo((Class<T>) values[0].getClass());
        if (info.isVirtualEntity()) {
            insert(null, info, values);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            insert(conn, info, values);
        } finally {
            closeSQLConnection(conn);
        }
    }

    /**
     * 新增对象， 必须是Entity对象
     *
     * @param <T>
     * @param conn
     * @param values
     */
    @Override
    public <T> void insert(final DataConnection conn, T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo((Class<T>) values[0].getClass());
        insert((Connection) conn.getConnection(), info, values);
    }

    private <T> void insert(final Connection conn, final EntityInfo<T> info, T... values) {
        if (values.length == 0) return;
        try {
            final EntityCache<T> cache = info.getCache();
            if (!info.isVirtualEntity()) {
                final String sql = info.insertSQL;
                final PreparedStatement prestmt = info.autoGenerated
                        ? conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) : conn.prepareStatement(sql);
                final Class primaryType = info.getPrimary().type();
                final Attribute primary = info.getPrimary();
                final boolean distributed = info.distributed;
                Attribute<T, Serializable>[] attrs = info.insertAttributes;
                String[] sqls = null;
                if (distributed && !info.initedPrimaryValue && primaryType.isPrimitive()) {
                    synchronized (info) {
                        if (!info.initedPrimaryValue) {
                            Statement stmt = conn.createStatement();
                            ResultSet rs = stmt.executeQuery("SELECT MAX(" + info.getPrimarySQLColumn() + ") FROM " + info.getTable());
                            if (rs.next()) {
                                if (primaryType == int.class) {
                                    int v = rs.getInt(1) / info.allocationSize;
                                    if (v > info.primaryValue.get()) info.primaryValue.set(v);
                                } else {
                                    long v = rs.getLong(1) / info.allocationSize;
                                    if (v > info.primaryValue.get()) info.primaryValue.set(v);
                                }
                            }
                            rs.close();
                            stmt.close();
                            if (info.distributeTables != null) {
                                for (final Class t : info.distributeTables) {
                                    EntityInfo<T> infox = loadEntityInfo(t);
                                    stmt = conn.createStatement();
                                    rs = stmt.executeQuery("SELECT MAX(" + info.getPrimarySQLColumn() + ") FROM " + infox.getTable()); // 必须是同一字段名
                                    if (rs.next()) {
                                        if (primaryType == int.class) {
                                            int v = rs.getInt(1) / info.allocationSize;
                                            if (v > info.primaryValue.get()) info.primaryValue.set(v);
                                        } else {
                                            long v = rs.getLong(1) / info.allocationSize;
                                            if (v > info.primaryValue.get()) info.primaryValue.set(v);
                                        }
                                    }
                                    rs.close();
                                    stmt.close();
                                }
                            }
                            info.initedPrimaryValue = true;
                        }
                    }
                }
                if (writeListener == null) {
                    for (final T value : values) {
                        int i = 0;
                        if (distributed) info.createPrimaryValue(value);
                        for (Attribute<T, Serializable> attr : attrs) {
                            prestmt.setObject(++i, attr.get(value));
                        }
                        prestmt.addBatch();
                    }
                } else {
                    char[] sqlchars = sql.toCharArray();
                    sqls = new String[values.length];
                    String[] ps = new String[attrs.length];
                    int index = 0;
                    for (final T value : values) {
                        int i = 0;
                        if (distributed) info.createPrimaryValue(value);
                        for (Attribute<T, Serializable> attr : attrs) {
                            Object a = attr.get(value);
                            ps[i] = formatToString(a);
                            prestmt.setObject(++i, a);
                        }
                        prestmt.addBatch();
                        //-----------------------------
                        StringBuilder sb = new StringBuilder(128);
                        i = 0;
                        for (char ch : sqlchars) {
                            if (ch == '?') {
                                sb.append(ps[i++]);
                            } else {
                                sb.append(ch);
                            }
                        }
                        sqls[index++] = sb.toString();
                    }
                }
                prestmt.executeBatch();
                if (writeListener != null) writeListener.insert(sqls);
                if (info.autoGenerated) {
                    ResultSet set = prestmt.getGeneratedKeys();
                    int i = -1;
                    while (set.next()) {
                        if (primaryType == int.class) {
                            primary.set(values[++i], set.getInt(1));
                        } else if (primaryType == long.class) {
                            primary.set(values[++i], set.getLong(1));
                        } else {
                            primary.set(values[++i], set.getObject(1));
                        }
                    }
                    set.close();
                }
                prestmt.close();
                //------------------------------------------------------------
                if (debug.get()) {
                    char[] sqlchars = sql.toCharArray();
                    for (final T value : values) {
                        //-----------------------------
                        StringBuilder sb = new StringBuilder(128);
                        int i = 0;
                        for (char ch : sqlchars) {
                            if (ch == '?') {
                                sb.append(formatToString(attrs[i++].get(value)));
                            } else {
                                sb.append(ch);
                            }
                        }
                        logger.finest(info.getType().getSimpleName() + " insert sql=" + sb.toString().replaceAll("(\r|\n)", "\\n"));
                    }
                }
            }
            if (cache != null) {
                for (final T value : values) {
                    cache.insert(value);
                }
                if (cacheListener != null) cacheListener.insertCache(info.getType(), values);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> void insertCache(Class<T> clazz, T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = EntityInfo.load(clazz, this.nodeid, this.cacheForbidden, fullloader);
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return;
        for (T value : values) {
            cache.insert(value);
        }
    }

    //-------------------------deleteCache--------------------------
    /**
     * 删除对象， 必须是Entity对象
     *
     * @param <T>
     * @param values
     */
    @Override
    public <T> void delete(T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo((Class<T>) values[0].getClass());
        if (info.isVirtualEntity()) {
            delete(null, info, values);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            delete(conn, info, values);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void delete(final DataConnection conn, T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo((Class<T>) values[0].getClass());
        delete((Connection) conn.getConnection(), info, values);
    }

    private <T> void delete(final Connection conn, final EntityInfo<T> info, T... values) {
        if (values.length == 0) return;
        final Attribute primary = info.getPrimary();
        Serializable[] ids = new Serializable[values.length];
        int i = 0;
        for (final T value : values) {
            ids[i++] = (Serializable) primary.get(value);
        }
        delete(conn, info, ids);
    }

    @Override
    public <T> void delete(Class<T> clazz, Serializable... ids) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (info.isVirtualEntity()) {
            delete(null, info, ids);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            delete(conn, info, ids);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void delete(final DataConnection conn, Class<T> clazz, Serializable... ids) {
        delete((Connection) conn.getConnection(), loadEntityInfo(clazz), ids);
    }

    private <T> void delete(final Connection conn, final EntityInfo<T> info, Serializable... keys) {
        if (keys.length == 0) return;
        try {
            if (!info.isVirtualEntity()) {
                String sql = "DELETE FROM " + info.getTable() + " WHERE " + info.getPrimarySQLColumn()
                        + " IN " + formatToString(keys);
                if (debug.get()) logger.finest(info.getType().getSimpleName() + " delete sql=" + sql);
                final Statement stmt = conn.createStatement();
                stmt.execute(sql);
                stmt.close();
                if (writeListener != null) writeListener.delete(sql);
            }
            //------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            final Attribute<T, Serializable> attr = info.getPrimary();
            final Serializable[] keys2 = keys;
            Serializable[] ids = cache.delete((T t) -> Arrays.binarySearch(keys2, attr.get(t)) >= 0);
            if (cacheListener != null) cacheListener.deleteCache(info.getType(), ids);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> void delete(Class<T> clazz, FilterNode node) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (info.isVirtualEntity()) {
            delete(null, info, node);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            delete(conn, info, node);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void delete(final DataConnection conn, Class<T> clazz, FilterNode node) {
        delete((Connection) conn.getConnection(), loadEntityInfo(clazz), node);
    }

    private <T> void delete(final Connection conn, final EntityInfo<T> info, FilterNode node) {
        try {
            if (!info.isVirtualEntity()) {
                String sql = "DELETE FROM " + info.getTable() + " a" + node.createFilterSQLExpress(info, null);
                if (debug.get()) logger.finest(info.getType().getSimpleName() + " delete sql=" + sql);
                final Statement stmt = conn.createStatement();
                stmt.execute(sql);
                stmt.close();
                if (writeListener != null) writeListener.delete(sql);
            }
            //------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            Serializable[] ids = cache.delete(node.createFilterPredicate(info, null));
            if (cacheListener != null) cacheListener.deleteCache(info.getType(), ids);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> void deleteCache(Class<T> clazz, Serializable... ids) {
        if (ids.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return;
        for (Serializable id : ids) {
            cache.delete(id);
        }
    }

    //------------------------update---------------------------
    /**
     * 更新对象， 必须是Entity对象
     *
     * @param <T>
     * @param values
     */
    @Override
    public <T> void update(T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo((Class<T>) values[0].getClass());
        if (info.isVirtualEntity()) {
            update(null, info, values);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            update(conn, info, values);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void update(final DataConnection conn, T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo((Class<T>) values[0].getClass());
        update((Connection) conn.getConnection(), info, values);
    }

    private <T> void update(final Connection conn, final EntityInfo<T> info, T... values) {
        try {
            Class clazz = info.getType();
            if (!info.isVirtualEntity()) {
                if (debug.get()) logger.finest(clazz.getSimpleName() + " update sql=" + info.updateSQL);
                final Attribute<T, Serializable> primary = info.getPrimary();
                final PreparedStatement prestmt = conn.prepareStatement(info.updateSQL);
                Attribute<T, Serializable>[] attrs = info.updateAttributes;
                String[] sqls = null;
                if (writeListener == null) {
                    for (final T value : values) {
                        int i = 0;
                        for (Attribute<T, Serializable> attr : attrs) {
                            prestmt.setObject(++i, attr.get(value));
                        }
                        prestmt.setObject(++i, primary.get(value));
                        prestmt.addBatch();
                    }
                } else {
                    char[] sqlchars = info.updateSQL.toCharArray();
                    sqls = new String[values.length];
                    String[] ps = new String[attrs.length];
                    int index = 0;
                    for (final T value : values) {
                        int i = 0;
                        for (Attribute<T, Serializable> attr : attrs) {
                            Object a = attr.get(value);
                            ps[i] = formatToString(a);
                            prestmt.setObject(++i, a);
                        }
                        prestmt.setObject(++i, primary.get(value));
                        prestmt.addBatch();
                        //-----------------------------
                        StringBuilder sb = new StringBuilder(128);
                        i = 0;
                        for (char ch : sqlchars) {
                            if (ch == '?') {
                                sb.append(ps[i++]);
                            } else {
                                sb.append(ch);
                            }
                        }
                        sqls[index++] = sb.toString();
                    }
                }
                prestmt.executeBatch();
                prestmt.close();
                if (writeListener != null) writeListener.update(sqls);
            }
            //---------------------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            for (final T value : values) {
                cache.update(value);
            }
            if (cacheListener != null) cacheListener.updateCache(clazz, values);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据主键值更新对象的column对应的值， 必须是Entity Class
     *
     * @param <T>
     * @param clazz
     * @param id
     * @param column
     * @param value
     */
    @Override
    public <T> void updateColumn(Class<T> clazz, Serializable id, String column, Serializable value) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (info.isVirtualEntity()) {
            updateColumn(null, info, id, column, value);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            updateColumn(conn, info, id, column, value);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void updateColumn(DataConnection conn, Class<T> clazz, Serializable id, String column, Serializable value) {
        updateColumn((Connection) conn.getConnection(), loadEntityInfo(clazz), id, column, value);
    }

    private <T> void updateColumn(Connection conn, final EntityInfo<T> info, Serializable id, String column, Serializable value) {
        try {
            if (!info.isVirtualEntity()) {
                String sql = "UPDATE " + info.getTable() + " SET " + info.getSQLColumn(column) + " = "
                        + formatToString(value) + " WHERE " + info.getPrimarySQLColumn() + " = " + formatToString(id);
                if (debug.get()) logger.finest(info.getType().getSimpleName() + " update sql=" + sql);
                final Statement stmt = conn.createStatement();
                stmt.execute(sql);
                stmt.close();
                if (writeListener != null) writeListener.update(sql);
            }
            //---------------------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            T rs = cache.update(id, (Attribute<T, Serializable>) info.getAttribute(column), value);
            if (cacheListener != null) cacheListener.updateCache(info.getType(), rs);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) closeSQLConnection(conn);
        }
    }

    /**
     * 根据主键值给对象的column对应的值+incvalue， 必须是Entity Class
     *
     * @param <T>
     * @param clazz
     * @param id
     * @param column
     * @param incvalue
     */
    @Override
    public <T> void updateColumnIncrement(Class<T> clazz, Serializable id, String column, long incvalue) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (info.isVirtualEntity()) {
            updateColumnIncrement(null, info, id, column, incvalue);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            updateColumnIncrement(conn, info, id, column, incvalue);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void updateColumnIncrement(DataConnection conn, Class<T> clazz, Serializable id, String column, long incvalue) {
        updateColumnIncrement((Connection) conn.getConnection(), loadEntityInfo(clazz), id, column, incvalue);
    }

    private <T> void updateColumnIncrement(Connection conn, final EntityInfo<T> info, Serializable id, String column, long incvalue) {
        try {
            if (!info.isVirtualEntity()) {
                String col = info.getSQLColumn(column);
                String sql = "UPDATE " + info.getTable() + " SET " + col + " = " + col + " + (" + incvalue
                        + ") WHERE " + info.getPrimarySQLColumn() + " = " + formatToString(id);
                if (debug.get()) logger.finest(info.getType().getSimpleName() + " update sql=" + sql);
                final Statement stmt = conn.createStatement();
                stmt.execute(sql);
                stmt.close();
                if (writeListener != null) writeListener.update(sql);
            }
            //---------------------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            Attribute<T, Serializable> attr = info.getAttribute(column);
            T value = cache.updateColumnIncrement(id, attr, incvalue);
            if (value != null && cacheListener != null) cacheListener.updateCache(info.getType(), value);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) closeSQLConnection(conn);
        }
    }

    /**
     * 根据主键值给对象的column对应的值 & andvalue， 必须是Entity Class
     *
     * @param <T>
     * @param clazz
     * @param id
     * @param column
     * @param andvalue
     */
    @Override
    public <T> void updateColumnAnd(Class<T> clazz, Serializable id, String column, long andvalue) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (info.isVirtualEntity()) {
            updateColumnAnd(null, info, id, column, andvalue);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            updateColumnAnd(conn, info, id, column, andvalue);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void updateColumnAnd(DataConnection conn, Class<T> clazz, Serializable id, String column, long andvalue) {
        updateColumnAnd((Connection) conn.getConnection(), loadEntityInfo(clazz), id, column, andvalue);
    }

    private <T> void updateColumnAnd(Connection conn, final EntityInfo<T> info, Serializable id, String column, long andvalue) {
        try {
            if (!info.isVirtualEntity()) {
                String col = info.getSQLColumn(column);
                String sql = "UPDATE " + info.getTable() + " SET " + col + " = " + col + " & (" + andvalue
                        + ") WHERE " + info.getPrimarySQLColumn() + " = " + formatToString(id);
                if (debug.get()) logger.finest(info.getType().getSimpleName() + " update sql=" + sql);
                final Statement stmt = conn.createStatement();
                stmt.execute(sql);
                stmt.close();
                if (writeListener != null) writeListener.update(sql);
            }
            //---------------------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            Attribute<T, Serializable> attr = info.getAttribute(column);
            T value = cache.updateColumnAnd(id, attr, andvalue);
            if (value != null && cacheListener != null) cacheListener.updateCache(info.getType(), value);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) closeSQLConnection(conn);
        }
    }

    /**
     * 根据主键值给对象的column对应的值 | andvalue， 必须是Entity Class
     *
     * @param <T>
     * @param clazz
     * @param id
     * @param column
     * @param orvalue
     */
    @Override
    public <T> void updateColumnOr(Class<T> clazz, Serializable id, String column, long orvalue) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        if (info.isVirtualEntity()) {
            updateColumnOr(null, info, id, column, orvalue);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            updateColumnOr(conn, info, id, column, orvalue);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void updateColumnOr(DataConnection conn, Class<T> clazz, Serializable id, String column, long orvalue) {
        updateColumnOr((Connection) conn.getConnection(), loadEntityInfo(clazz), id, column, orvalue);
    }

    private <T> void updateColumnOr(Connection conn, final EntityInfo<T> info, Serializable id, String column, long orvalue) {
        try {
            if (!info.isVirtualEntity()) {
                String col = info.getSQLColumn(column);
                String sql = "UPDATE " + info.getTable() + " SET " + col + " = " + col + " | (" + orvalue
                        + ") WHERE " + info.getPrimarySQLColumn() + " = " + formatToString(id);
                if (debug.get()) logger.finest(info.getType().getSimpleName() + " update sql=" + sql);
                final Statement stmt = conn.createStatement();
                stmt.execute(sql);
                stmt.close();
                if (writeListener != null) writeListener.update(sql);
            }
            //---------------------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            Attribute<T, Serializable> attr = info.getAttribute(column);
            T value = cache.updateColumnOr(id, attr, orvalue);
            if (value != null && cacheListener != null) cacheListener.updateCache(info.getType(), value);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) closeSQLConnection(conn);
        }
    }

    /**
     * 更新对象指定的一些字段， 必须是Entity对象
     *
     * @param <T>
     * @param value
     * @param columns
     */
    @Override
    public <T> void updateColumns(final T value, final String... columns) {
        final EntityInfo<T> info = loadEntityInfo((Class<T>) value.getClass());
        if (info.isVirtualEntity()) {
            updateColumns(null, info, value, columns);
            return;
        }
        Connection conn = createWriteSQLConnection();
        try {
            updateColumns(conn, info, value, columns);
        } finally {
            closeSQLConnection(conn);
        }
    }

    @Override
    public <T> void updateColumns(final DataConnection conn, final T value, final String... columns) {
        updateColumns((Connection) conn.getConnection(), loadEntityInfo((Class<T>) value.getClass()), value, columns);
    }

    private <T> void updateColumns(final Connection conn, final EntityInfo<T> info, final T value, final String... columns) {
        if (value == null || columns.length < 1) return;
        try {
            final Class<T> clazz = (Class<T>) value.getClass();
            StringBuilder setsql = new StringBuilder();
            final Serializable id = info.getPrimary().get(value);
            final List<Attribute<T, Serializable>> attrs = new ArrayList<>();
            final boolean virtual = info.isVirtualEntity();
            for (String col : columns) {
                Attribute<T, Serializable> attr = info.getUpdateAttribute(col);
                if (attr == null) continue;
                attrs.add(attr);
                if (!virtual) {
                    if (setsql.length() > 0) setsql.append(',');
                    setsql.append(info.getSQLColumn(col)).append(" = ").append(formatToString(attr.get(value)));
                }
            }
            if (!virtual) {
                String sql = "UPDATE " + info.getTable() + " SET " + setsql
                        + " WHERE " + info.getPrimarySQLColumn() + " = " + formatToString(id);
                if (debug.get()) logger.finest(value.getClass().getSimpleName() + ": " + sql);
                final Statement stmt = conn.createStatement();
                stmt.execute(sql);
                stmt.close();
                if (writeListener != null) writeListener.update(sql);
            }
            //---------------------------------------------------
            final EntityCache<T> cache = info.getCache();
            if (cache == null) return;
            cache.update(value, attrs);
            if (cacheListener != null) cacheListener.updateCache(clazz, value);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> void updateCache(Class<T> clazz, T... values) {
        if (values.length == 0) return;
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return;
        for (T value : values) {
            cache.update(value);
        }
    }

    public <T> void reloadCache(Class<T> clazz, Serializable... ids) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (cache == null) return;
        String column = info.getPrimary().field();
        for (Serializable id : ids) {
            Sheet<T> sheet = querySheet(false, clazz, null, FLIPPER_ONE, FilterNode.create(column, id), null);
            T value = sheet.isEmpty() ? null : sheet.list().get(0);
            if (value != null) cache.update(value);
        }
    }

    //-----------------------getNumberResult-----------------------------
    @Override
    public Number getNumberResult(final Class entityClass, final Reckon reckon, final String column) {
        return getNumberResult(entityClass, reckon, column, null, null);
    }

    @Override
    public Number getNumberResult(final Class entityClass, final Reckon reckon, final String column, FilterBean bean) {
        return getNumberResult(entityClass, reckon, column, null, bean);
    }

    @Override
    public Number getNumberResult(final Class entityClass, final Reckon reckon, final String column, FilterNode node) {
        return getNumberResult(entityClass, reckon, column, node, null);
    }

    private <T> Number getNumberResult(final Class<T> entityClass, final Reckon reckon, final String column, FilterNode node, FilterBean bean) {
        final Connection conn = createReadSQLConnection();
        try {
            final EntityInfo<T> info = loadEntityInfo(entityClass);
            if (node == null && bean != null) node = loadFilterBeanNode(bean.getClass());
            final EntityCache<T> cache = info.getCache();
            if (cache != null && (info.isVirtualEntity() || cache.isFullLoaded())) {
                Predicate<T> filter = node == null ? null : node.createFilterPredicate(info, bean);
                if (node == null || node.isJoinAllCached()) {
                    return cache.getNumberResult(reckon, column == null ? null : info.getAttribute(column), filter);
                }
            }
            final String sql = "SELECT " + reckon.getColumn((column == null || column.isEmpty() ? "*" : ("a." + column))) + " FROM " + info.getTable() + " a"
                    + (node == null ? "" : node.createFilterSQLExpress(info, bean));
            if (debug.get() && info.isLoggable(Level.FINEST)) logger.finest(entityClass.getSimpleName() + " single sql=" + sql);
            final PreparedStatement prestmt = conn.prepareStatement(sql);
            Number rs = null;
            ResultSet set = prestmt.executeQuery();
            if (set.next()) {
                rs = (Number) set.getObject(1);
            }
            set.close();
            prestmt.close();
            return rs;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) closeSQLConnection(conn);
        }
    }

    //-----------------------getMapResult-----------------------------
    @Override
    public <K extends Serializable, V extends Number> Map<K, V> getMapResult(Class entityClass, final String keyColumn, Reckon reckon, final String reckonColumn) {
        return getMapResult(entityClass, keyColumn, reckon, reckonColumn, null, null);
    }

    @Override
    public <K extends Serializable, V extends Number> Map<K, V> getMapResult(Class entityClass, final String keyColumn, Reckon reckon, final String reckonColumn, FilterBean bean) {
        return getMapResult(entityClass, keyColumn, reckon, reckonColumn, null, bean);
    }

    @Override
    public <K extends Serializable, V extends Number> Map<K, V> getMapResult(Class entityClass, final String keyColumn, Reckon reckon, final String reckonColumn, FilterNode node) {
        return getMapResult(entityClass, keyColumn, reckon, reckonColumn, node, null);
    }

    private <K extends Serializable, V extends Number> Map<K, V> getMapResult(final Class entityClass, final String keyColumn, final Reckon reckon, final String reckonColumn, FilterNode node, FilterBean bean) {
        final Connection conn = createReadSQLConnection();
        try {
            final EntityInfo info = loadEntityInfo(entityClass);
            if (node == null && bean != null) node = loadFilterBeanNode(bean.getClass());
            final EntityCache cache = info.getCache();
            if (cache != null && (info.isVirtualEntity() || cache.isFullLoaded())) {
                Predicate filter = node == null ? null : node.createFilterPredicate(info, bean);
                if (node == null || node.isJoinAllCached()) {
                    return cache.getMapResult(info.getAttribute(keyColumn), reckon, reckonColumn == null ? null : info.getAttribute(reckonColumn), filter);
                }
            }
            final String sqlkey = info.getSQLColumn(keyColumn);
            final String sql = "SELECT a." + sqlkey + ", " + reckon.getColumn((reckonColumn == null || reckonColumn.isEmpty() ? "*" : ("a." + reckonColumn)))
                    + " FROM " + info.getTable() + " a" + (node == null ? "" : node.createFilterSQLExpress(info, bean)) + " GROUP BY a." + sqlkey;
            if (debug.get() && info.isLoggable(Level.FINEST)) logger.finest(entityClass.getSimpleName() + " single sql=" + sql);
            final PreparedStatement prestmt = conn.prepareStatement(sql);
            Map<K, V> rs = new LinkedHashMap<>();
            ResultSet set = prestmt.executeQuery();
            ResultSetMetaData rsd = set.getMetaData();
            boolean smallint = rsd.getColumnType(1) == Types.SMALLINT;
            while (set.next()) {
                rs.put((K) (smallint ? set.getShort(1) : set.getObject(1)), (V) set.getObject(2));
            }
            set.close();
            prestmt.close();
            return rs;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) closeSQLConnection(conn);
        }
    }

    //-----------------------find----------------------------
    /**
     * 根据主键获取对象
     *
     * @param <T>
     * @param clazz
     * @param pk
     * @return
     */
    @Override
    public <T> T find(Class<T> clazz, Serializable pk) {
        return find(clazz, (SelectColumn) null, pk);
    }

    @Override
    public <T> T find(Class<T> clazz, final SelectColumn selects, Serializable pk) {
        String column = loadEntityInfo(clazz).getPrimary().field();
        Sheet<T> sheet = querySheet(clazz, selects, FLIPPER_ONE, FilterNode.create(column, pk));
        return sheet.isEmpty() ? null : sheet.list().get(0);
    }

    @Override
    public <T> T findByColumn(Class<T> clazz, String column, Serializable key) {
        return find(clazz, FilterNode.create(column, key));
    }

    @Override
    public <T> T find(final Class<T> clazz, final FilterBean bean) {
        Sheet<T> sheet = querySheet(clazz, FLIPPER_ONE, bean);
        return sheet.isEmpty() ? null : sheet.list().get(0);
    }

    @Override
    public <T> T find(final Class<T> clazz, final FilterNode node) {
        Sheet<T> sheet = querySheet(clazz, FLIPPER_ONE, node);
        return sheet.isEmpty() ? null : sheet.list().get(0);
    }

    @Override
    public <T> boolean exists(Class<T> clazz, Serializable pk) {
        return find(clazz, pk) != null;
    }

    @Override
    public <T> boolean exists(final Class<T> clazz, final FilterNode node) {
        return find(clazz, node) != null;
    }

    @Override
    public <T> boolean exists(final Class<T> clazz, final FilterBean bean) {
        return find(clazz, bean) != null;
    }

    //-----------------------list set----------------------------
    @Override
    public <T, V> HashSet<V> queryColumnSet(String selectedColumn, Class<T> clazz, String column, Serializable key) {
        return queryColumnSet(selectedColumn, clazz, FilterNode.create(column, key));
    }

    @Override
    public <T, V> HashSet<V> queryColumnSet(String selectedColumn, Class<T> clazz, FilterBean bean) {
        return new LinkedHashSet<>(queryColumnList(selectedColumn, clazz, bean));
    }

    @Override
    public <T, V> HashSet<V> queryColumnSet(String selectedColumn, Class<T> clazz, FilterNode node) {
        return new LinkedHashSet<>(queryColumnList(selectedColumn, clazz, node));
    }

    @Override
    public <T, V> List<V> queryColumnList(String selectedColumn, Class<T> clazz, String column, Serializable key) {
        return queryColumnList(selectedColumn, clazz, FilterNode.create(column, key));
    }

    @Override
    public <T, V> List<V> queryColumnList(String selectedColumn, Class<T> clazz, FilterBean bean) {
        return (List<V>) queryColumnSheet(selectedColumn, clazz, null, bean).list(true);
    }

    @Override
    public <T, V> List<V> queryColumnList(String selectedColumn, Class<T> clazz, FilterNode node) {
        return (List<V>) queryColumnSheet(selectedColumn, clazz, null, node).list(true);
    }

    /**
     * 根据过滤对象FilterBean查询对象集合
     *
     * @param <K>
     * @param <T>
     * @param clazz
     * @param bean
     * @return
     */
    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final FilterBean bean) {
        return queryMap(clazz, null, bean);
    }

    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final FilterNode node) {
        return queryMap(clazz, null, node);
    }

    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return formatMap(clazz, querySheet(clazz, selects, null, bean).getRows());
    }

    @Override
    public <K extends Serializable, T> Map<K, T> queryMap(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        return formatMap(clazz, querySheet(clazz, selects, null, node).getRows());
    }

    private <K extends Serializable, T> Map<K, T> formatMap(final Class<T> clazz, Collection<T> list) {
        Map<K, T> map = new LinkedHashMap<>();
        if (list == null || list.isEmpty()) return map;
        final Attribute<T, K> attr = (Attribute<T, K>) loadEntityInfo(clazz).getPrimary();
        for (T t : list) {
            map.put(attr.get(t), t);
        }
        return map;
    }

    /**
     * 根据指定字段值查询对象集合
     *
     * @param <T>
     * @param clazz
     * @param column
     * @param key
     * @return
     */
    @Override
    public <T> List<T> queryList(Class<T> clazz, String column, Serializable key) {
        return queryList(clazz, FilterNode.create(column, key));
    }

    /**
     * 根据过滤对象FilterBean查询对象集合
     *
     * @param <T>
     * @param clazz
     * @param bean
     * @return
     */
    @Override
    public <T> List<T> queryList(final Class<T> clazz, final FilterBean bean) {
        return queryList(clazz, null, bean);
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final FilterNode node) {
        return queryList(clazz, null, node);
    }

    /**
     * 根据过滤对象FilterBean查询对象集合， 对象只填充或排除SelectColumn指定的字段
     *
     * @param <T>
     * @param clazz
     * @param selects
     * @param bean
     * @return
     */
    @Override
    public <T> List<T> queryList(final Class<T> clazz, final SelectColumn selects, final FilterBean bean) {
        return querySheet(clazz, selects, null, bean).list(true);
    }

    @Override
    public <T> List<T> queryList(final Class<T> clazz, final SelectColumn selects, final FilterNode node) {
        return querySheet(clazz, selects, null, node).list(true);
    }

    //-----------------------sheet----------------------------
    /**
     * 根据指定参数查询对象某个字段的集合
     * <p>
     * @param <T>
     * @param <V>
     * @param selectedColumn
     * @param clazz
     * @param flipper
     * @param bean
     * @return
     */
    @Override
    public <T, V> Sheet<V> queryColumnSheet(String selectedColumn, Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return queryColumnSheet(selectedColumn, clazz, flipper, null, bean);
    }

    @Override
    public <T, V> Sheet<V> queryColumnSheet(String selectedColumn, Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return queryColumnSheet(selectedColumn, clazz, flipper, node, null);
    }

    private <T, V> Sheet<V> queryColumnSheet(String selectedColumn, Class<T> clazz, final Flipper flipper, final FilterNode node, final FilterBean bean) {
        Sheet<T> sheet = querySheet(true, clazz, SelectColumn.createIncludes(selectedColumn), flipper, node, bean);
        final Sheet<V> rs = new Sheet<>();
        if (sheet.isEmpty()) return rs;
        rs.setTotal(sheet.getTotal());
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final Attribute<T, V> selected = (Attribute<T, V>) info.getAttribute(selectedColumn);
        final List<V> list = new ArrayList<>();
        for (T t : sheet.getRows()) {
            list.add(selected.get(t));
        }
        rs.setRows(list);
        return rs;
    }

    /**
     * 根据过滤对象FilterBean和翻页对象Flipper查询一页的数据
     *
     * @param <T>
     * @param clazz
     * @param flipper
     * @param bean
     * @return
     */
    @Override
    public <T> Sheet<T> querySheet(Class<T> clazz, final Flipper flipper, final FilterBean bean) {
        return querySheet(clazz, null, flipper, bean);
    }

    @Override
    public <T> Sheet<T> querySheet(Class<T> clazz, final Flipper flipper, final FilterNode node) {
        return querySheet(clazz, null, flipper, node);
    }

    /**
     * 根据过滤对象FilterBean和翻页对象Flipper查询一页的数据， 对象只填充或排除SelectColumn指定的字段
     *
     * @param <T>
     * @param clazz
     * @param selects
     * @param flipper
     * @param bean
     * @return
     */
    @Override
    public <T> Sheet<T> querySheet(Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterBean bean) {
        return querySheet(true, clazz, selects, flipper, null, bean);
    }

    @Override
    public <T> Sheet<T> querySheet(Class<T> clazz, final SelectColumn selects, final Flipper flipper, final FilterNode node) {
        return querySheet(true, clazz, selects, flipper, node, null);
    }

    private <T> Sheet<T> querySheet(boolean readcache, Class<T> clazz, final SelectColumn selects, final Flipper flipper, FilterNode node, final FilterBean bean) {
        final EntityInfo<T> info = loadEntityInfo(clazz);
        final EntityCache<T> cache = info.getCache();
        if (node == null && bean != null) node = loadFilterBeanNode(bean.getClass());
        if (readcache && cache != null) {
            Predicate<T> filter = node == null ? null : node.createFilterPredicate(info, bean);
            if (node == null || node.isJoinAllCached()) {
                Sheet<T> sheet = cache.querySheet(selects, filter, flipper, FilterNode.createFilterComparator(info, flipper));
                if (!sheet.isEmpty() || info.isVirtualEntity() || cache.isFullLoaded()) return sheet;
            }
        }
        final Connection conn = createReadSQLConnection();
        try {
            final SelectColumn sels = selects;
            final List<T> list = new ArrayList();
            final String sql = "SELECT a.* FROM " + info.getTable() + " a"
                    + (node == null ? "" : node.createFilterSQLExpress(info, bean)) + createFilterSQLOrderBy(info, flipper);
            if (debug.get() && info.isLoggable(Level.FINEST))
                logger.finest(clazz.getSimpleName() + " query sql=" + sql + (flipper == null ? "" : (" LIMIT " + flipper.index() + "," + flipper.getSize())));
            final PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
            final ResultSet set = ps.executeQuery();
            if (flipper != null && flipper.index() > 0) set.absolute(flipper.index());
            final int limit = flipper == null ? Integer.MAX_VALUE : flipper.getSize();
            int i = 0;
            long total;
            while (set.next()) {
                i++;
                list.add(info.getValue(sels, set));
                if (limit <= i) break;
            }
            if (flipper != null) {
                set.last();
                total = set.getRow();
            } else {
                total = list.size();
            }
            set.close();
            ps.close();
            return new Sheet<>(total, list);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            closeSQLConnection(conn);
        }
    }
}