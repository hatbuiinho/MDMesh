package com.hmdm.persistence;
import com.hmdm.persistence.domain.AppInstallRequest;
import com.hmdm.persistence.mapper.AppInstallMapper;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class AppInstallDAOTest {
    private AppInstallRequest row(){AppInstallRequest r=new AppInstallRequest();r.setDeviceNumber("a");r.setConfigurationId(1);return r;}
    @Test public void duplicatePendingOrApprovedRequestIsNotInserted(){AppInstallMapper m=mock(AppInstallMapper.class);AppInstallRequest r=row();when(m.open(r)).thenReturn(1);assertEquals(0,new AppInstallDAO(m).submit(r));verify(m).lockDevice("a");verify(m,never()).insert(any());}
    @Test public void submissionLimitPreventsUnboundedRequests(){AppInstallMapper m=mock(AppInstallMapper.class);when(m.recentCount(eq("a"),anyLong())).thenReturn(30);assertEquals(-1,new AppInstallDAO(m).submit(row()));verify(m,never()).insert(any());}
    @Test public void onlySuccessfulDecisionWritesAudit(){AppInstallMapper m=mock(AppInstallMapper.class);AppInstallRequest r=row();AppInstallDAO dao=new AppInstallDAO(m);assertEquals(0,dao.decide(r,"pending"));verify(m,never()).audit(any());when(m.decide(r,"pending")).thenReturn(1);assertEquals(1,dao.decide(r,"pending"));verify(m).audit(r);}
}
