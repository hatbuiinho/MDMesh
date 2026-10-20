package com.hmdm.persistence;
import com.hmdm.persistence.mapper.NetworkAccessMapper;
import com.hmdm.persistence.domain.NetworkAccessRequest;
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

public class NetworkAccessDatabaseTest {
    private PGSimpleDataSource ds;private String schema;private SqlSession session;private NetworkAccessDAO dao;
    @Before public void setup() throws Exception {
        String url=System.getenv("NETWORK_ACCESS_TEST_JDBC");Assume.assumeTrue("Set NETWORK_ACCESS_TEST_JDBC",url!=null);
        ds=new PGSimpleDataSource();ds.setURL(url);ds.setUser("postgres");ds.setPassword("rest-test");
        schema="network_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}ds.setCurrentSchema(schema);
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){
            s.execute("CREATE TABLE configurations(id INT PRIMARY KEY); INSERT INTO configurations VALUES(1),(2)");
            s.execute("CREATE TABLE devices(number VARCHAR(255) PRIMARY KEY,configurationId INT,customerId INT,description TEXT); INSERT INTO devices VALUES('a',1,1,'P Chân'),('b',1,2,'Other tenant')");
            Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("../server/src/main/resources/liquibase/db.changelog.xml"));
            NodeList changes=doc.getElementsByTagName("changeSet");
            for(int i=0;i<changes.getLength();i++){Element e=(Element)changes.item(i);if(e.getAttribute("id").equals("26.10.20-network-access-requests"))s.execute(e.getElementsByTagName("sql").item(0).getTextContent());}
        }
        org.apache.ibatis.session.Configuration cfg=new org.apache.ibatis.session.Configuration(new Environment("network",new JdbcTransactionFactory(),ds));cfg.addMapper(NetworkAccessMapper.class);
        session=new SqlSessionFactoryBuilder().build(cfg).openSession();dao=new NetworkAccessDAO(session.getMapper(NetworkAccessMapper.class));
    }
    @After public void cleanup()throws Exception{if(session!=null)session.close();if(ds!=null&&schema!=null)try(Connection c=ds.getConnection();Statement s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
    private NetworkRule rule(){NetworkRule r=new NetworkRule();r.setId("chat");r.setName("Liên lạc");r.setPackages(Arrays.asList("com.example.chat"));r.setDomains(Arrays.asList("video.example.com"));return r;}
    private NetworkAccessRequest request(String device,String hash){NetworkAccessRequest row=new NetworkAccessRequest();row.setDeviceNumber(device);row.setConfigurationId(1);row.setPolicyHash(hash);row.setPackageName("com.example.chat");row.setDomain("video.example.com");row.setReason("Need this video");row.setCreatedAt(System.currentTimeMillis());return row;}
    private NetworkAccessRequest approve(NetworkAccessRequest row,Long expires){row.setStatus("approved");row.setGrantPackages("[\"com.example.chat\"]");row.setDecisionBy("admin");row.setDecisionNote("Approved");row.setExpiresAt(expires);row.setDecidedAt(System.currentTimeMillis());assertEquals(1,dao.decide(row,"pending"));return row;}
    @Test public void grantsAreDeviceAndPolicyBoundAndIndefiniteSurvivesReload(){
        dao.save(1,Arrays.asList(rule()));DesiredWebAccess before=dao.decorate(1,"a",null);assertNotNull(before.getPolicyHash());
        assertEquals(1,dao.insert(request("a",before.getPolicyHash())));assertEquals(0,dao.insert(request("a",before.getPolicyHash())));
        NetworkAccessRequest row=dao.deviceRequests("a").get(0);approve(row,null);session.commit();session.clearCache();
        DesiredWebAccess applied=dao.decorate(1,"a",null);assertEquals(before.getPolicyHash(),applied.getPolicyHash());assertEquals(1,applied.getGrants().size());assertNull(applied.getGrants().get(0).getExpiresAt());
        assertTrue(dao.decorate(1,"b",null).getGrants().isEmpty());assertTrue(dao.decorate(2,"a",null)==null);
        NetworkRule changed=rule();changed.setDomains(Arrays.asList("different.example.com"));dao.save(1,Arrays.asList(changed));assertTrue(dao.decorate(1,"a",null).getGrants().isEmpty());
        dao.save(1,Arrays.asList(rule()));assertTrue("Restoring old policy must not resurrect old approval",dao.decorate(1,"a",null).getGrants().isEmpty());
        assertEquals(1,dao.auditTrail(row.getId()).size());
    }
    @Test public void expiryRevocationConcurrencyAndTenantIsolation(){
        dao.save(1,Arrays.asList(rule()));String hash=dao.decorate(1,"a",null).getPolicyHash();dao.insert(request("a",hash));dao.insert(request("b",hash));
        NetworkAccessRequest row=dao.deviceRequests("a").get(0);approve(row,System.currentTimeMillis()-1);assertTrue(dao.decorate(1,"a",null).getGrants().isEmpty());
        assertEquals(0,dao.decide(row,"pending"));assertEquals(1,dao.auditTrail(row.getId()).size());
        row.setStatus("revoked");assertEquals(1,dao.decide(row,"approved"));assertEquals(2,dao.auditTrail(row.getId()).size());
        assertEquals(1,dao.list(1,null).size());assertEquals("P Chân",dao.list(1,null).get(0).getDeviceName());assertTrue(dao.list(1,"b").isEmpty());
    }
    @Test public void submissionRateIsSerializedAndDuplicatesRemainIdempotent(){
        dao.save(1,Arrays.asList(rule()));String hash=dao.decorate(1,"a",null).getPolicyHash();
        for(int i=0;i<30;i++){NetworkAccessRequest r=request("a",hash);r.setDomain("host"+i+".example.com");assertEquals(1,dao.submit(r));}
        NetworkAccessRequest duplicate=request("a",hash);duplicate.setDomain("host0.example.com");assertEquals(0,dao.submit(duplicate));
        NetworkAccessRequest excess=request("a",hash);excess.setDomain("overflow.example.com");assertEquals(-1,dao.submit(excess));
        assertEquals(30,dao.deviceRequests("a").size());
    }
}
