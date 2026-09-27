package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentReleaseDAO;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class AgentReleaseResourceTest {
    private Path root;
    private AgentRelease stored;
    private AgentReleaseResource resource;
    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("agent-upload-test-");
        AgentReleaseDAO dao = new AgentReleaseDAO(null) {
            @Override public void register(AgentRelease r) { r.setId(1); stored = r; }
        };
        resource = new AgentReleaseResource(dao, root.toString(), "https://mdm.test");
        UserRole role = new UserRole(); role.setSuperAdmin(true);
        User user = new User(); user.setCustomerId(1); user.setUserRole(role); SecurityContext.init(user);
    }
    @After public void cleanup() throws Exception {
        SecurityContext.release();
        if (root != null) try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
    @Test public void rejectsNonApkWithoutPublishing() throws Exception {
        Response response = resource.upload(new ByteArrayInputStream("not an apk".getBytes("UTF-8")));
        assertEquals(Response.ResponseStatus.ERROR, response.getStatus()); assertNull(stored);
        try (java.util.stream.Stream<Path> files = Files.walk(root)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }
    @Test public void readOnlyUserCannotUpload() {
        SecurityContext.init(1);
        assertEquals(Response.PERMISSION_DENIED().getStatus(), resource.upload(new ByteArrayInputStream(new byte[0])).getStatus());
        assertNull(stored);
    }
    @Test public void verifiesRealSignedApkAndPublishesMatchingMetadata() throws Exception {
        String fixture = System.getenv("ROLLOUT_TEST_APK");
        Assume.assumeTrue("Set ROLLOUT_TEST_APK to verify a real signed agent", fixture != null);
        byte[] bytes = Files.readAllBytes(Paths.get(fixture));
        Response response = resource.upload(new ByteArrayInputStream(bytes));
        assertEquals(response.getMessage(), Response.ResponseStatus.OK, response.getStatus());
        assertNotNull(stored); assertEquals("com.mdmesh.agent", stored.getPackageName());
        assertTrue(stored.getVersionCode() > 0); assertEquals(64,stored.getSha256().length());
        assertEquals(43,stored.getSignatureChecksum().length());
        assertArrayEquals(bytes,Files.readAllBytes(root.resolve(stored.getFilePath())));
        assertEquals("https://mdm.test/files/" + stored.getFilePath(), ((Map<?,?>)response.getData()).get("url"));
        // Corrupt a signed APK byte: signature verification must refuse it and publish nothing new.
        stored = null; bytes[100] ^= 1;
        assertEquals(Response.ResponseStatus.ERROR, resource.upload(new ByteArrayInputStream(bytes)).getStatus());
        assertNull(stored);
        try (java.util.stream.Stream<Path> files = Files.walk(root)) { assertEquals(1,files.filter(Files::isRegularFile).count()); }
    }
}
