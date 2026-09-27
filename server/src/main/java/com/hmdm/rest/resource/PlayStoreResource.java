package com.hmdm.rest.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.android.apksig.ApkVerifier;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.rest.json.APKFileDetails;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.APKFileAnalyzer;
import com.hmdm.util.FileUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.*;

/** Optional proxy/import boundary for the self-hosted Aurora Play Bridge. */
@Api(tags = {"Play Store"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/play")
public class PlayStoreResource {
    private static final int MAX_RESULTS = 60;
    private static final long MAX_APK_BYTES = 500L * 1024L * 1024L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CustomerDAO customerDAO;
    private final APKFileAnalyzer apkAnalyzer;
    private final String filesDirectory;
    private final String baseUrl;
    private final String bridgeUrl;
    private final String bridgeKey;

    public PlayStoreResource() {
        this.customerDAO = null;
        this.apkAnalyzer = null;
        this.filesDirectory = "";
        this.baseUrl = "";
        this.bridgeUrl = "";
        this.bridgeKey = "";
    }

    @Inject
    public PlayStoreResource(CustomerDAO customerDAO, APKFileAnalyzer apkAnalyzer,
                             @Named("files.directory") String filesDirectory,
                             @Named("base.url") String baseUrl) {
        this.customerDAO = customerDAO;
        this.apkAnalyzer = apkAnalyzer;
        this.filesDirectory = filesDirectory;
        this.baseUrl = baseUrl;
        this.bridgeUrl = trimSlash(env("PLAY_BRIDGE_URL"));
        this.bridgeKey = env("PLAY_BRIDGE_API_KEY");
    }

    @GET @Path("/status") @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation("Get Play Store integration status")
    public Response status() {
        if (!SecurityContext.get().getCurrentUser().isPresent()) return Response.PERMISSION_DENIED();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("profile", envOr("PLAY_DEVICE_PROFILE", "arm64-v8a"));
        if (!enabled()) {
            out.put("available", false);
            out.put("message", "Set PLAY_STORE_ENABLED=true, PLAY_BRIDGE_URL and PLAY_BRIDGE_API_KEY.");
            return Response.OK(out);
        }
        try {
            JsonNode health = getJson(bridgeUrl + "/v1/health");
            out.put("available", health.path("ok").asBoolean(true));
            out.put("message", health.path("message").asText("Play Bridge is ready."));
        } catch (Exception e) {
            out.put("available", false);
            out.put("message", "Play Bridge is unavailable.");
        }
        return Response.OK(out);
    }

    @GET @Path("/search") @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation("Search Google Play through Play Bridge")
    public Response search(@QueryParam("q") String query, @QueryParam("limit") Integer requestedLimit) {
        if (!SecurityContext.get().hasPermission("applications")) return Response.PERMISSION_DENIED();
        if (!enabled()) return Response.ERROR("error.play.disabled");
        String q = query == null ? "" : query.trim();
        if (q.length() < 2 || q.length() > 200) return Response.ERROR("error.play.query");
        int limit = Math.max(1, Math.min(requestedLimit == null ? 30 : requestedLimit, MAX_RESULTS));
        try {
            String url = bridgeUrl + "/v1/search?q=" + enc(q) + "&limit=" + limit;
            JsonNode result = getJson(url);
            return Response.OK(mapper.convertValue(result, new TypeReference<Object>() {}));
        } catch (BridgeException e) {
            return Response.ERROR(e.messageKey);
        } catch (Exception e) {
            return Response.ERROR("error.play.unavailable");
        }
    }

    @POST @Path("/import") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation("Import a free Google Play release into MDMesh storage")
    public Response importApp(ImportRequest request) {
        if (!SecurityContext.get().hasPermission("edit_applications") ||
                !SecurityContext.get().hasPermission("edit_files")) return Response.PERMISSION_DENIED();
        if (!enabled()) return Response.ERROR("error.play.disabled");
        if (request == null || !validPackage(request.packageName)) return Response.ERROR("error.play.package");

        List<File> temporary = new ArrayList<>();
        try {
            JsonNode manifest = getJson(bridgeUrl + "/v1/apps/" + enc(request.packageName) + "/release");
            if (manifest.path("paid").asBoolean(false)) return Response.ERROR("error.play.paid");
            JsonNode artifacts = manifest.path("artifacts");
            if (!artifacts.isArray() || artifacts.size() == 0 || artifacts.size() > 100) {
                return Response.ERROR("error.play.artifacts");
            }

            APKFileDetails metadata = null;
            String signer = null;
            List<Map<String, Object>> hostedParts = new ArrayList<>();
            Customer customer = customerDAO.findById(SecurityContext.get().getCurrentCustomerId().get());
            for (JsonNode artifact : artifacts) {
                String artifactId = artifact.path("id").asText("");
                if (!artifactId.matches("[A-Za-z0-9._-]{1,160}")) throw new BridgeException("error.play.artifacts");
                File temp = downloadArtifact(request.packageName, artifactId);
                temporary.add(temp);
                String currentSigner = signerFingerprint(temp);
                if (signer == null) signer = currentSigner;
                else if (!signer.equals(currentSigner)) throw new BridgeException("error.play.signerMismatch");
                APKFileDetails current = null;
                try { current = apkAnalyzer.analyzeFile(temp.getAbsolutePath()); } catch (Exception ignored) { }
                if (current != null && current.getPkg() != null) {
                    if (!request.packageName.equals(current.getPkg())) throw new BridgeException("error.play.packageMismatch");
                    if (metadata == null || "base".equalsIgnoreCase(artifactId)) metadata = current;
                    if (metadata != null && current.getVersionCode() != metadata.getVersionCode()) {
                        throw new BridgeException("error.play.versionMismatch");
                    }
                }
                String hash = sha256(temp);
                String fileName = request.packageName + "-" + hash + ".apk";
                String url = host(temp, fileName, customer);
                temporary.remove(temp);
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("url", url); part.put("sha256", hash); part.put("name", fileName);
                hostedParts.add(part);
            }
            if (metadata == null) throw new BridgeException("error.play.invalidApk");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", manifest.path("name").asText(request.packageName));
            out.put("packageName", metadata.getPkg());
            out.put("version", metadata.getVersion());
            out.put("versionCode", metadata.getVersionCode());
            out.put("iconUrl", manifest.path("iconUrl").asText(null));
            out.put("parts", hostedParts);
            return Response.OK(out);
        } catch (BridgeException e) {
            return Response.ERROR(e.messageKey);
        } catch (Exception e) {
            return Response.ERROR("error.play.import");
        } finally {
            for (File file : temporary) file.delete();
        }
    }

    private File downloadArtifact(String packageName, String artifactId) throws Exception {
        HttpURLConnection connection = open(bridgeUrl + "/v1/apps/" + enc(packageName) +
                "/artifacts/" + enc(artifactId));
        int status = connection.getResponseCode();
        if (status != 200) throw bridgeError(status);
        long declared = connection.getContentLengthLong();
        if (declared > MAX_APK_BYTES) throw new BridgeException("error.play.tooLarge");
        File out = FileUtil.createTempFile("play-apk");
        long total = 0;
        try (InputStream in = connection.getInputStream(); OutputStream sink = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > MAX_APK_BYTES) throw new BridgeException("error.play.tooLarge");
                sink.write(buffer, 0, n);
            }
        } finally { connection.disconnect(); }
        return out;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw bridgeError(status);
            if (connection.getContentLengthLong() > 2L * 1024L * 1024L) throw new BridgeException("error.play.responseTooLarge");
            try (InputStream in = new BoundedInputStream(connection.getInputStream(), 2L * 1024L * 1024L)) {
                return mapper.readTree(in);
            }
        } finally { connection.disconnect(); }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000); connection.setReadTimeout(120_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + bridgeKey);
        connection.setRequestProperty("User-Agent", "MDMesh-PlayImporter");
        return connection;
    }

    private String host(File temp, String fileName, Customer customer) throws Exception {
        File destination = FileUtil.resolveFile(customer, filesDirectory, null, fileName);
        if (destination.exists()) temp.delete();
        else if (FileUtil.moveFile(customer, filesDirectory, null, temp.getAbsolutePath(), fileName) == null)
            throw new IOException("Could not host artifact");
        String prefix = customer.getFilesDir();
        return prefix == null || prefix.isEmpty() ? baseUrl + "/files/" + fileName
                : baseUrl + "/files/" + prefix + "/" + fileName;
    }

    private boolean enabled() { return "true".equalsIgnoreCase(env("PLAY_STORE_ENABLED")) && !bridgeUrl.isEmpty() && !bridgeKey.isEmpty(); }
    private static String env(String key) { String value = System.getenv(key); return value == null ? "" : value.trim(); }
    private static String envOr(String key, String fallback) { String value = env(key); return value.isEmpty() ? fallback : value; }
    private static String trimSlash(String value) { while (value.endsWith("/")) value = value.substring(0, value.length() - 1); return value; }
    private static String enc(String value) throws UnsupportedEncodingException { return URLEncoder.encode(value, "UTF-8"); }
    private static boolean validPackage(String value) { return value != null && value.length() <= 255 && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"); }
    private static BridgeException bridgeError(int status) { return new BridgeException(status == 401 || status == 403 ? "error.play.auth" : status == 429 ? "error.play.rateLimited" : "error.play.unavailable"); }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) >= 0) digest.update(b, 0, n); }
        StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format("%02x", b)); return out.toString();
    }

    private static String signerFingerprint(File file) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(file).build().verify();
        if (!result.isVerified() || result.getSignerCertificates().isEmpty()) {
            throw new BridgeException("error.play.invalidSignature");
        }
        X509Certificate certificate = result.getSignerCertificates().get(0);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(digest.digest(certificate.getEncoded()));
    }

    public static class ImportRequest { public String packageName; }
    private static class BridgeException extends Exception { final String messageKey; BridgeException(String key) { super(key); this.messageKey = key; } }
    private static class BoundedInputStream extends FilterInputStream {
        private final long max; private long count;
        BoundedInputStream(InputStream in, long max) { super(in); this.max = max; }
        public int read() throws IOException { int value = super.read(); if (value >= 0 && ++count > max) throw new IOException("response too large"); return value; }
        public int read(byte[] b, int o, int l) throws IOException { int n = super.read(b, o, l); if (n > 0 && (count += n) > max) throw new IOException("response too large"); return n; }
    }
}
