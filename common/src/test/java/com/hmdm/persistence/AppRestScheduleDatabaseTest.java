package com.hmdm.persistence;

import com.hmdm.persistence.mapper.AppUsageMapper;
import com.hmdm.rest.json.agent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

/** Uses an isolated opt-in PostgreSQL database, including the actual migration SQL. */
public class AppRestScheduleDatabaseTest {
    private PGSimpleDataSource ds;
    private String schema;
    private SqlSession session;
    private AppUsageDAO dao;
    @Before public void setup() throws Exception {
        String url=System.getenv("REST_SCHEDULE_TEST_JDBC");
        Assume.assumeTrue("Set REST_SCHEDULE_TEST_JDBC for database tests", url!=null);
        ds=new PGSimpleDataSource(); ds.setURL(url); ds.setUser("postgres"); ds.setPassword("rest-test");
        schema="rest_test_"+UUID.randomUUID().toString().replace("-", "");
        try(Connection c=ds.getConnection(); Statement s=c.createStatement()) { s.execute("CREATE SCHEMA "+schema); }
        ds.setCurrentSchema(schema);
        try(Connection c=ds.getConnection(); Statement s=c.createStatement()) {
            s.execute("CREATE TABLE configurations(id INT PRIMARY KEY); INSERT INTO configurations VALUES(1),(2)");
            s.execute("CREATE TABLE devices(number VARCHAR(255) PRIMARY KEY,configurationId INT)");
            File changelog=new File("../server/src/main/resources/liquibase/db.changelog.xml");
            Document document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(changelog);
            NodeList changes=document.getElementsByTagName("changeSet");
            for(int i=0;i<changes.getLength();i++) {
                Element e=(Element)changes.item(i); String id=e.getAttribute("id");
                if(id.equals("26.09.28-app-usage-policy") || id.equals("26.10.17-app-rest-schedules"))
                    s.execute(e.getElementsByTagName("sql").item(0).getTextContent());
            }
        }
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration(new Environment("rest",new JdbcTransactionFactory(),ds));
        config.addMapper(AppUsageMapper.class);
        session=new SqlSessionFactoryBuilder().build(config).openSession();
        dao=new AppUsageDAO(session.getMapper(AppUsageMapper.class));
    }
    @After public void cleanup() throws Exception {
        if(session!=null) session.close();
        if(ds!=null && schema!=null) try(Connection c=ds.getConnection(); Statement s=c.createStatement()) { s.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
    @Test public void storesScheduleWithoutAppRulesAndClearsItWithoutLosingTimezone() {
        DesiredAppUsage p=new DesiredAppUsage(); p.setTimezone("Asia/Ho_Chi_Minh");
        AppRestSchedule schedule=new AppRestSchedule(); schedule.setId("sleep"); schedule.setName("Giờ ngủ");
        schedule.setDays(Arrays.asList(1,2,3)); schedule.setFrom("22:00"); schedule.setTo("06:00");
        p.getSchedules().add(schedule); dao.replace(1,p); session.commit();
        assertEquals("sleep",dao.desired(1).getSchedules().get(0).getId());
        assertTrue(dao.desired(1).getRules().isEmpty());
        assertNull(dao.desired(2));
        p.setSchedules(Collections.emptyList()); dao.replace(1,p); session.commit();
        assertTrue(dao.desired(1).getSchedules().isEmpty());
        assertEquals("Asia/Ho_Chi_Minh",dao.desired(1).getTimezone());
    }
    @Test public void retainsExistingAppRulesAndCascadesConfigurationDeletion() throws Exception {
        AppUsageRule rule=new AppUsageRule(); rule.setPackageName("com.example.game"); rule.setDailyLimitMinutes(60);
        DesiredAppUsage p=new DesiredAppUsage(); p.getRules().add(rule); dao.replace(1,p); session.commit();
        assertEquals(Integer.valueOf(60),dao.desired(1).getRules().get(0).getDailyLimitMinutes());
        try(Connection c=ds.getConnection(); Statement s=c.createStatement()) { s.execute("DELETE FROM configurations WHERE id=1"); }
        session.clearCache(); assertNull(dao.desired(1));
    }
}
