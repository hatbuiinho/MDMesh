package com.hmdm.persistence;

import com.hmdm.persistence.domain.*;
import com.hmdm.persistence.mapper.*;
import com.hmdm.util.AgentRolloutPolicy;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.io.File;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

/** Opt-in against an isolated PostgreSQL database, never the deployment database. */
public class AgentRolloutDatabaseTest {
    private PGSimpleDataSource ds;
    private String schema;
    private SqlSessionFactory factory;
    private SqlSession session;
    private RolloutMapper mapper;
    private AgentRolloutCoordinator coordinator;
    @Before public void setup() throws Exception {
        String url = System.getenv("ROLLOUT_TEST_JDBC");
        Assume.assumeTrue("Set ROLLOUT_TEST_JDBC to run PostgreSQL integration tests", url != null);
        ds = new PGSimpleDataSource(); ds.setURL(url); ds.setUser("postgres"); ds.setPassword("rollout-test");
        schema = "rollout_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { s.execute("CREATE SCHEMA " + schema); }
        ds.setCurrentSchema(schema);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE customers(id INT PRIMARY KEY); INSERT INTO customers VALUES(1),(2)");
            s.execute("CREATE TABLE devices(number VARCHAR(255) PRIMARY KEY, customerId INT, agentCapabilities TEXT, description TEXT)");
            s.execute("CREATE TABLE agentRollout(id SERIAL PRIMARY KEY, customerId INT, targetVersion TEXT, packageName TEXT, apkUrl TEXT, apkSha256 TEXT, apkVersionCode INT, stage TEXT, createdAt BIGINT, updatedAt BIGINT)");
            s.execute("CREATE UNIQUE INDEX uq_agentRollout_active ON agentRollout(customerId) WHERE stage IN ('canary','fleet')");
            s.execute("CREATE TABLE agentRolloutCanary(rolloutId INT, deviceNumber TEXT, PRIMARY KEY(rolloutId,deviceNumber))");
            s.execute("CREATE TABLE agentCommand(id SERIAL PRIMARY KEY, deviceNumber TEXT, type TEXT, payload TEXT, requiresCapability TEXT, status TEXT, detail TEXT, createdAt BIGINT, deliveredAt BIGINT, completedAt BIGINT)");
            s.execute("CREATE TABLE device_state(deviceNumber TEXT PRIMARY KEY, battery INT, charging BOOLEAN, locked BOOLEAN, kioskActive BOOLEAN, androidRelease TEXT, lastBootAt BIGINT, updatedAt BIGINT, agentVersion TEXT, powerMode TEXT, telemetry TEXT, appliedConfigRevision TEXT, appliedConfigAt BIGINT)");
            // Execute the actual migration, not a copied version of its SQL.
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("../server/src/main/resources/liquibase/db.changelog.xml"));
            NodeList changes = doc.getElementsByTagName("changeSet");
            boolean found = false;
            for (int i = 0; i < changes.getLength(); i++) {
                Element e = (Element) changes.item(i);
                if ("26.09.27-agent-releases-rollout".equals(e.getAttribute("id"))) {
                    s.execute(e.getElementsByTagName("sql").item(0).getTextContent()); found = true;
                }
            }
            assertTrue(found);
            s.execute("INSERT INTO devices VALUES ('canary',1,'{\"appManagement\":[\"silentInstall\"]}','Canary tablet'),('offline',1,'{\"appManagement\":[\"silentInstall\"]}',NULL),('other-tenant',2,'{}',NULL)");
        }
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.addMapper(RolloutMapper.class); config.addMapper(AgentCommandMapper.class);
        config.addMapper(DeviceStateMapper.class); config.addMapper(AgentReleaseMapper.class);
        factory = new SqlSessionFactoryBuilder().build(config);
        session = factory.openSession();
        mapper = session.getMapper(RolloutMapper.class); coordinator = new AgentRolloutCoordinator(mapper);
        state("canary", 1000); state("offline", 1000); session.commit();
    }
    @After public void cleanup() throws Exception {
        if (session != null) session.close();
        if (ds != null && schema != null) try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    private void state(String number, long code) {
        DeviceState s = new DeviceState(); s.setDeviceNumber(number); s.setAgentVersion("1.0." + (code - 1000));
        s.setAgentVersionCode(code); s.setAgentPackageName("com.mdmesh.agent"); s.setAgentSignatureChecksum("key");
        s.setUpdatedAt(System.currentTimeMillis()); session.getMapper(DeviceStateMapper.class).upsert(s);
    }
    private AgentRollout create(boolean all) {
        AgentRollout r = new AgentRollout(); r.setCustomerId(1); r.setTargetVersion("1.0.1"); r.setPackageName("com.mdmesh.agent");
        r.setApkUrl("https://test/files/agent-releases/immutable.apk"); r.setApkSha256(String.join("", Collections.nCopies(64,"a")));
        r.setApkSignatureChecksum("key"); r.setApkVersionCode(1001); r.setStage(all ? "fleet" : "canary");
        r.setCreatedAt(System.currentTimeMillis()); r.setUpdatedAt(r.getCreatedAt());
        coordinator.create(r, all ? Collections.emptyList() : Collections.singletonList("canary")); session.commit(); return r;
    }
    private void sql(String sql) throws Exception {
        try (Statement s = session.getConnection().createStatement()) { s.execute(sql); }
        session.clearCache();
    }
    @Test public void canaryPromotionOfflineExpiryAndCompletion() throws Exception {
        AgentRollout r = create(false);
        assertEquals(2, mapper.targets(r.getId()).size()); // tenant boundary
        coordinator.reconcile(1,"offline");
        assertNull(mapper.target(r.getId(),"offline").getCommandStatus());
        try { coordinator.promote(1,r.getId()); fail("premature promotion"); } catch (IllegalArgumentException expected) { }
        coordinator.reconcile(1,"canary"); coordinator.reconcile(1,"canary");
        assertEquals(1,session.getMapper(AgentCommandMapper.class).listPending("canary").size());
        state("canary",1001); coordinator.reconcile(1,"canary");
        assertEquals("done",mapper.target(r.getId(),"canary").getCommandStatus());
        coordinator.promote(1,r.getId()); session.commit();
        // Device comes back days later, independently of whether any console is open.
        coordinator.reconcile(1,"offline"); session.commit();
        sql("UPDATE agentCommand SET createdAt = 1 WHERE deviceNumber = 'offline'");
        session.getMapper(AgentCommandMapper.class).expireStale("offline",2,2,System.currentTimeMillis());
        coordinator.reconcile(1,"offline");
        assertEquals(1, session.getMapper(AgentCommandMapper.class).listPending("offline").size());
        assertEquals("pending",mapper.target(r.getId(),"offline").getCommandStatus());
        state("offline",1002); coordinator.reconcile(1,"offline");
        assertEquals("updated",AgentRolloutPolicy.status(r,mapper.target(r.getId(),"offline"),System.currentTimeMillis()));
        coordinator.stop(1,r.getId(),true); session.commit();
        assertEquals("done",mapper.findById(r.getId()).getStage());
        assertTrue(session.getMapper(AgentCommandMapper.class).listPending("offline").isEmpty());
    }
    @Test public void otherInstallThenFailureRetryAndCancel() throws Exception {
        AgentRollout r = create(true);
        sql("INSERT INTO agentCommand(deviceNumber,type,status,createdAt) VALUES('offline','app.install','accepted',1)");
        coordinator.reconcile(1,"offline"); assertNull(mapper.target(r.getId(),"offline").getCommandStatus());
        sql("UPDATE agentCommand SET status='done' WHERE deviceNumber='offline'");
        coordinator.reconcile(1,"offline");
        assertEquals("pending",mapper.target(r.getId(),"offline").getCommandStatus());
        sql("UPDATE agentCommand SET status='failed',detail='signature rejected',deliveredAt=2 WHERE rolloutId="+r.getId());
        coordinator.reconcile(1,"offline");
        assertEquals("failed",mapper.target(r.getId(),"offline").getCommandStatus());
        coordinator.retry(1,r.getId(),"offline"); coordinator.reconcile(1,"offline");
        assertEquals("pending",mapper.target(r.getId(),"offline").getCommandStatus());
        try { coordinator.stop(1,r.getId(),true); fail("unfinished rollout"); } catch (IllegalArgumentException expected) { }
        coordinator.stop(1,r.getId(),false); coordinator.reconcile(1,"canary");
        assertTrue(session.getMapper(AgentCommandMapper.class).listPending("offline").isEmpty());
        assertTrue(session.getMapper(AgentCommandMapper.class).listPending("canary").isEmpty());
        assertEquals("cancelled",mapper.findById(r.getId()).getStage());
    }
    @Test public void releaseCatalogIsTenantScopedAndVersionImmutable() {
        AgentReleaseMapper rm = session.getMapper(AgentReleaseMapper.class);
        AgentRelease r = new AgentRelease(); r.setCustomerId(1); r.setVersionCode(1000); r.setVersionName("1.0.0");
        r.setPackageName("com.mdmesh.agent"); r.setSignatureChecksum("key"); r.setSha256("hash");
        r.setFilePath("agent-releases/one.apk"); r.setCreatedAt(1L);
        new AgentReleaseDAO(rm).register(r); session.commit();
        assertNotNull(rm.find(1,r.getId())); assertNull(rm.find(2,r.getId()));
        assertEquals("agent-releases/one.apk",rm.list(1).get(0).getFilePath());
        try { new AgentReleaseDAO(rm).register(r); fail("overwrote release"); } catch (IllegalArgumentException expected) { }
    }
    @Test public void simultaneousCheckinsQueueExactlyOneInstall() throws Exception {
        AgentRollout r = create(true);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> {
                try (SqlSession concurrent = factory.openSession()) {
                    start.await();
                    new AgentRolloutCoordinator(concurrent.getMapper(RolloutMapper.class)).reconcile(1,"offline");
                    concurrent.commit();
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
            start.countDown();
            for (java.util.concurrent.Future<?> task : tasks) task.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, session.getMapper(AgentCommandMapper.class).listPending("offline").size());
            assertEquals("pending",mapper.target(r.getId(),"offline").getCommandStatus());
        } finally { pool.shutdownNow(); }
    }

    @Test public void staleConfigApplyIsRequeuedWithoutTouchingOtherCommands() throws Exception {
        sql("INSERT INTO agentCommand(deviceNumber,type,status,createdAt,deliveredAt) VALUES" +
                "('offline','config.apply','delivered',1,10)," +
                "('offline','app.install','delivered',1,10)");
        AgentCommandMapper commands = session.getMapper(AgentCommandMapper.class);
        assertEquals(1, commands.requeueStaleConfigApply("offline", 11));
        assertEquals(1, commands.listPending("offline").size());
        assertEquals("config.apply", commands.listPending("offline").get(0).getType());
        assertEquals(0, commands.requeueStaleConfigApply("offline", 11));
    }
}
