package com.hmdm.persistence;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.security.SecurityContext;
import javax.servlet.*;
import javax.servlet.http.*;
import org.junit.*;
import static org.mockito.Mockito.*;

public class AgentAdminAccessSessionTest {
    UserDAO users;AgentAdminSessionDAO sessions;AuthFilter filter;HttpServletRequest request;HttpServletResponse response;HttpSession cookie;FilterChain chain;User user;
    @Before public void setup() throws Exception {
        SecurityContext.release();users=mock(UserDAO.class);sessions=mock(AgentAdminSessionDAO.class);filter=new AuthFilter(users);
        java.lang.reflect.Field field=AuthFilter.class.getDeclaredField("agentAdminSessions");field.setAccessible(true);field.set(filter,sessions);
        request=mock(HttpServletRequest.class);response=mock(HttpServletResponse.class);cookie=mock(HttpSession.class);chain=mock(FilterChain.class);
        user=new User();user.setId(7);user.setCustomerId(1);user.setAuthToken("valid-version");when(cookie.getAttribute(AuthFilter.sessionCredentials)).thenReturn(user);when(request.getSession(false)).thenReturn(cookie);when(users.getUserDetails(7)).thenReturn(user);
    }
    @After public void cleanup(){SecurityContext.release();}
    @Test public void revokedRefreshFamilyImmediatelyRejectsExistingCookie() throws Exception {
        when(cookie.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE)).thenReturn("revoked-family");when(sessions.active("revoked-family",user)).thenReturn(false);
        filter.doFilter(request,response,chain);verify(response).sendError(403);verifyNoInteractions(chain);
    }
    @Test public void validAgentSessionStillUsesNormalAuthorizationChain() throws Exception {
        when(cookie.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE)).thenReturn("active-family");when(sessions.active("active-family",user)).thenReturn(true);
        filter.doFilter(request,response,chain);verify(chain).doFilter(request,response);verifyNoInteractions(response);
    }
    @Test public void legacyBrowserCookieDoesNotNeedAgentRefreshFamily() throws Exception {
        filter.doFilter(request,response,chain);verify(chain).doFilter(request,response);verifyNoInteractions(sessions,response);
    }
}
