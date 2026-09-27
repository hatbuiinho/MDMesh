package com.hmdm.rest.resource;

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;
import com.hmdm.persistence.AgentReleaseDAO;
import com.hmdm.persistence.domain.AgentRelease;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Tenant-scoped release catalog. APK bytes and metadata are published together, never overwritten. */
@Singleton
@Path("/private/agent/v1/releases")
@Produces(MediaType.APPLICATION_JSON)
public class AgentReleaseResource {
    private static final Logger LOG = LoggerFactory.getLogger(AgentReleaseResource.class);
    private final AgentReleaseDAO dao;
    private final String filesDirectory;
    private final String baseUrl;
    @Inject public AgentReleaseResource(AgentReleaseDAO dao, @Named("files.directory") String filesDirectory,
                                       @Named("base.url") String baseUrl) {
        this.dao = dao; this.filesDirectory = filesDirectory; this.baseUrl = baseUrl;
    }
    @GET public Response list() {
        Optional<Integer> customer = SecurityContext.get().getCurrentCustomerId();
        if (!customer.isPresent()) return Response.PERMISSION_DENIED();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentRelease r : dao.list(customer.get())) out.add(view(r, baseUrl));
        return Response.OK(out);
    }
    public static Map<String, Object> view(AgentRelease r, String baseUrl) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", r.getId()); v.put("packageName", r.getPackageName());
        v.put("versionName", r.getVersionName()); v.put("versionCode", r.getVersionCode());
        v.put("sha256", r.getSha256()); v.put("signatureChecksum", r.getSignatureChecksum());
        v.put("url", baseUrl.replaceAll("/+$", "") + "/files/" + r.getFilePath());
        v.put("createdAt", r.getCreatedAt());
        return v;
    }
    @POST @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(@FormDataParam("file") InputStream input) {
        Optional<Integer> customer = SecurityContext.get().getCurrentCustomerId();
        if (!customer.isPresent() || !SecurityContext.get().hasPermission("edit_devices")) return Response.PERMISSION_DENIED();
        if (input == null) return Response.ERROR("Select an APK file.");
        java.nio.file.Path tmp = null, dest = null;
        boolean committed = false;
        try {
            java.nio.file.Path root = new File(filesDirectory, "agent-releases").toPath();
            Files.createDirectories(root);
            // Staging bytes are not under the public files directory.
            tmp = Files.createTempFile("mdmesh-agent-", ".apk");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[65536]; long size = 0; int n;
                while ((n = input.read(buffer)) != -1) {
                    size += n;
                    if (size > 128L * 1024 * 1024) throw new IllegalArgumentException("APK exceeds 128 MiB.");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
            ApkVerifier.Result verified = new ApkVerifier.Builder(tmp.toFile()).build().verify();
            if (!verified.isVerified() || verified.getSignerCertificates().size() != 1)
                throw new IllegalArgumentException("APK signature is invalid or the APK has multiple signers.");
            AgentRelease release = new AgentRelease();
            try (ApkFile apk = new ApkFile(tmp.toFile())) {
                ApkMeta meta = apk.getApkMeta();
                if (!("com.mdmesh.agent".equals(meta.getPackageName()) || "com.mdmesh.agent.debug".equals(meta.getPackageName())))
                    throw new IllegalArgumentException("Select an MDMesh agent APK.");
                if (meta.getVersionCode() == null || meta.getVersionCode() <= 0 || meta.getVersionCode() > Integer.MAX_VALUE
                        || meta.getVersionName() == null || meta.getVersionName().length() > 255)
                    throw new IllegalArgumentException("Invalid APK version metadata.");
                if (!apk.getManifestXml().contains("com.mdmesh.agent.admin.AdminReceiver"))
                    throw new IllegalArgumentException("APK is missing the MDMesh device admin receiver.");
                release.setPackageName(meta.getPackageName()); release.setVersionName(meta.getVersionName());
                release.setVersionCode(meta.getVersionCode().intValue());
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
            release.setSha256(hex.toString());
            release.setSignatureChecksum(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verified.getSignerCertificates().get(0).getEncoded())));
            String name = UUID.randomUUID().toString() + ".apk";
            dest = root.resolve(name);
            Files.copy(tmp, dest); // new random path; never replace an existing release
            release.setFilePath("agent-releases/" + name);
            release.setCustomerId(customer.get()); release.setCreatedAt(System.currentTimeMillis());
            dao.register(release);
            committed = true;
            return Response.OK(view(release, baseUrl));
        } catch (IllegalArgumentException e) {
            return Response.ERROR(e.getMessage());
        } catch (ApkFormatException e) {
            return Response.ERROR("The uploaded file is not a valid signed APK.");
        } catch (Exception e) {
            LOG.warn("Agent APK registration failed", e);
            return Response.ERROR("Unable to verify or store this APK. Check the server log.");
        } finally {
            try { if (tmp != null) Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            try { if (!committed && dest != null) Files.deleteIfExists(dest); } catch (IOException ignored) { }
        }
    }
}
