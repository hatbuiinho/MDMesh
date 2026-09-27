package com.hmdm.persistence;
import com.hmdm.persistence.domain.AgentRelease;
import com.hmdm.persistence.mapper.AgentReleaseMapper;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

public class AgentReleaseDAOTest {
    private AgentRelease release(int code, String signer) {
        AgentRelease r = new AgentRelease(); r.setCustomerId(1); r.setPackageName("com.mdmesh.agent");
        r.setVersionCode(code); r.setVersionName("v" + code); r.setSignatureChecksum(signer); return r;
    }
    @Test public void rejectsWrongKeyAndNonIncreasingCode() {
        List<AgentRelease> inserted = new ArrayList<>();
        AgentReleaseMapper mapper = new AgentReleaseMapper() {
            public boolean isPublishedPath(String path) { return false; }
            public Integer lockCustomer(int id) { return id; }
            public List<AgentRelease> list(int id) { return Collections.singletonList(release(1000, "key")); }
            public AgentRelease find(int customer, int id) { return null; }
            public void insert(AgentRelease r) { inserted.add(r); }
        };
        AgentReleaseDAO dao = new AgentReleaseDAO(mapper);
        for (AgentRelease invalid : new AgentRelease[]{release(1001, "other"), release(1000, "key"), release(999, "key")}) {
            try { dao.register(invalid); fail("accepted incompatible release"); }
            catch (IllegalArgumentException expected) { }
        }
        assertTrue(inserted.isEmpty());
        AgentRelease valid = release(1001, "key"); dao.register(valid);
        assertEquals(Collections.singletonList(valid), inserted);
    }
}
