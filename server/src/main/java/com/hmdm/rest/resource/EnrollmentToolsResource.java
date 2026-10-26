package com.hmdm.rest.resource;

import com.hmdm.rest.json.Response;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Public, credential-free enrollment tools built from the same source as the server image. */
@Singleton
@Path("/public/tools/adb/windows")
public class EnrollmentToolsResource {
    private static final String NAME = "MDMesh-Enroll-Windows.zip";
    private final File archive;

    @Inject
    public EnrollmentToolsResource() {
        this(new File("/usr/local/share/mdmesh/enrollment-tools", NAME));
    }

    EnrollmentToolsResource(File archive) {
        this.archive = archive;
    }

    @GET
    @Path("/latest")
    @Produces("application/zip")
    public javax.ws.rs.core.Response latest(@Context Request request) throws IOException {
        if (!archive.isFile() || !archive.canRead()) {
            return javax.ws.rs.core.Response.status(404).type(MediaType.APPLICATION_JSON)
                    .entity(Response.ERROR("Windows ADB enrollment tools are not available on this server."))
                    .header("Cache-Control", "no-store").build();
        }
        String checksum = sha256(archive);
        EntityTag tag = new EntityTag(checksum);
        javax.ws.rs.core.Response.ResponseBuilder cached = request.evaluatePreconditions(tag);
        if (cached != null) {
            return cached.tag(tag).header("Cache-Control", "no-cache").build();
        }
        return javax.ws.rs.core.Response.ok(archive, "application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + NAME + "\"")
                .header("Content-Length", archive.length())
                .header("X-Checksum-SHA256", checksum)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "no-cache")
                .tag(tag).build();
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
