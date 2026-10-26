package com.hmdm.rest.resource;

import com.hmdm.persistence.*;
import com.hmdm.persistence.mapper.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.filter.AuthFilter;
import javax.servlet.http.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AgentAdminSessionResourceTest {
    AgentAdminSessionDAO dao;UserMapper users;AgentAdminSessionResource resource;HttpServletRequest request;HttpSession session;User user;
    @Before public void setup(){
        dao=mock(AgentAdminSessionDAO.class);users=mock(UserMapper.class);resource=new AgentAdminSessionResource(dao,users);request=mock(HttpServletRequest.class);session=mock(HttpSession.class);when(request.getSession(false)).thenReturn(session);
        user=new User();user.setId(7);user.setCustomerId(1);user.setAuthToken("session-version");UserRole role=new UserRole();role.setSuperAdmin(true);user.setUserRole(role);when(session.getAttribute(AuthFilter.sessionCredentials)).thenReturn(user);when(users.findById(7)).thenReturn(user);
    }
    @Test public void expiredSessionAndIncompleteTwoFactorCannotIssueRefresh(){
        when(request.getSession(false)).thenReturn(null);assertEquals(401,resource.register(request,null).getStatus());
        when(request.getSession(false)).thenReturn(session);when(session.getAttribute(AuthFilter.twoFactorNeeded)).thenReturn("true");assertEquals(401,resource.register(request,null).getStatus());verifyNoInteractions(dao);
    }
    @Test public void changedAuthTokenIsRejectedWithoutIssuingRefresh(){
        User previous=new User();previous.setId(7);previous.setAuthToken("old-version");when(session.getAttribute(AuthFilter.sessionCredentials)).thenReturn(previous);
        assertEquals(401,resource.status(request).getStatus());verifyNoInteractions(dao);
    }
    @Test public void permissionCheckDoesNotMistakeValidSessionForExpiry(){assertEquals(200,resource.status(request).getStatus());}
    @Test public void revokedFamilyMakesExistingCookieInvalid(){when(session.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE)).thenReturn("revoked-family");when(dao.active("revoked-family",user)).thenReturn(false);assertEquals(401,resource.status(request).getStatus());}
    @Test public void accessCookieCannotExtendAbsoluteRefreshLifetimeByRegisteringAgain(){
        when(session.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE)).thenReturn("active-family");when(dao.active("active-family",user)).thenReturn(true);
        assertEquals(409,resource.register(request,null).getStatus());verify(dao,never()).issue(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test public void agentCannotRegisterInWebPermissionNamespace(){
        AgentAdminSessionResource.Registration body=new AgentAdminSessionResource.Registration();body.installationId="web_installation-fixture-1234";
        assertEquals(400,resource.register(request,body).getStatus());verify(dao,never()).issue(any(),any());
    }
    @Test public void invalidRefreshNeverCreatesCookieSession(){assertEquals(401,resource.refresh(request,"Bearer invalid").getStatus());verify(request,never()).getSession(true);verify(session,never()).invalidate();}
}
