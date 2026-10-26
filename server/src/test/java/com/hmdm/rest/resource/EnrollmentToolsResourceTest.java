package com.hmdm.rest.resource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Response;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

public class EnrollmentToolsResourceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void missingBundleReturnsJson404WithoutCaching() throws Exception {
        Response response = new EnrollmentToolsResource(new File(folder.getRoot(), "missing.zip"))
                .latest(mock(Request.class));
        assertEquals(404, response.getStatus());
        assertEquals("application/json", response.getMediaType().toString());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
    }

    @Test public void downloadHasStableNameAndChecksumOfActualBytes() throws Exception {
        File archive = folder.newFile("bundle.zip");
        Files.write(archive.toPath(), new byte[]{'a', 'b', 'c'});
        Response response = new EnrollmentToolsResource(archive).latest(mock(Request.class));
        assertEquals(200, response.getStatus());
        assertEquals(archive, response.getEntity());
        assertEquals("application/zip", response.getMediaType().toString());
        assertEquals("attachment; filename=\"MDMesh-Enroll-Windows.zip\"", response.getHeaderString("Content-Disposition"));
        assertEquals("3", response.getHeaderString("Content-Length"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", response.getHeaderString("X-Checksum-SHA256"));
        assertEquals(response.getHeaderString("X-Checksum-SHA256"), response.getEntityTag().getValue());
        assertEquals("no-cache", response.getHeaderString("Cache-Control"));
    }

    @Test public void matchingEtagSkipsTransferringTheArchive() throws Exception {
        File archive = folder.newFile("bundle.zip");
        Files.write(archive.toPath(), new byte[]{1, 2, 3});
        Request request = mock(Request.class);
        when(request.evaluatePreconditions(any(EntityTag.class))).thenReturn(Response.notModified());
        Response response = new EnrollmentToolsResource(archive).latest(request);
        assertEquals(304, response.getStatus());
        assertNull(response.getEntity());
        assertNotNull(response.getEntityTag());
        assertEquals("no-cache", response.getHeaderString("Cache-Control"));
    }
}
