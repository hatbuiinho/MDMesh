package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentRelease;
import com.hmdm.persistence.mapper.AgentReleaseMapper;
import org.mybatis.guice.transactional.Transactional;
import java.util.List;

@Singleton
public class AgentReleaseDAO {
    private final AgentReleaseMapper mapper;
    @Inject public AgentReleaseDAO(AgentReleaseMapper mapper) { this.mapper = mapper; }
    public boolean isPublishedPath(String path) {
        return path != null && path.matches("agent-releases/[a-f0-9-]{36}\\.apk") && mapper.isPublishedPath(path);
    }
    public List<AgentRelease> list(int customerId) { return mapper.list(customerId); }
    public AgentRelease find(int customerId, int id) { return mapper.find(customerId, id); }

    @Transactional
    public void register(AgentRelease release) {
        mapper.lockCustomer(release.getCustomerId());
        for (AgentRelease existing : list(release.getCustomerId())) {
            if (!existing.getPackageName().equals(release.getPackageName())) continue;
            if (!existing.getSignatureChecksum().equals(release.getSignatureChecksum()))
                throw new IllegalArgumentException("Signing certificate differs from the registered agent. Use the original keystore.");
            if (existing.getVersionName().equals(release.getVersionName()))
                throw new IllegalArgumentException("Use a new versionName so older agents can report rollout progress correctly.");
            if (existing.getVersionCode() >= release.getVersionCode())
                throw new IllegalArgumentException("versionCode must be greater than every registered version of this package.");
        }
        mapper.insert(release);
    }
}
