package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentAdminSessionDAO;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;
import javax.servlet.http.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WebSessionResourceTest {
    AgentAdminSessionDAO dao; WebSessionResource resource; HttpServletRequest request; HttpSession session; HttpServletResponse response;
    @Before public void setup(){
        dao=mock(AgentAdminSessionDAO.class);resource=new WebSessionResource(dao,mock(UserMapper.class));
        response=mock(HttpServletResponse.class);request=mock(HttpServletRequest.class);session=mock(HttpSession.class);
        when(request.getContextPath()).thenReturn("");when(request.getSession(false)).thenReturn(session);
    }
    @Test public void crossOriginFormsCannotMutateSessions(){
        assertEquals(403,resource.register(request,response,null).getStatus());
        assertEquals(403,resource.refresh(request,response,null).getStatus());
        assertEquals(403,resource.revoke(request,response,null).getStatus());verifyNoInteractions(dao);
    }
    @Test public void incompleteTwoFactorCannotIssueRefresh(){
        when(session.getAttribute(AuthFilter.sessionCredentials)).thenReturn(new User());
        when(session.getAttribute(AuthFilter.twoFactorNeeded)).thenReturn("true");
        assertEquals(401,resource.register(request,response,"1").getStatus());verifyNoInteractions(dao);
    }
    @Test public void registrationCannotExtendExistingFamily(){
        when(session.getAttribute(AuthFilter.sessionCredentials)).thenReturn(new User());
        when(session.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE)).thenReturn("family");
        assertEquals(409,resource.register(request,response,"1").getStatus());verifyNoInteractions(dao);
    }
    @Test public void invalidRefreshClearsHttpOnlyCookieWithoutCreatingSession(){
        javax.ws.rs.core.Response result=resource.refresh(request,response,"1");
        assertNull(result.getHeaderString("Set-Cookie")); // Preserve the servlet-generated JSESSIONID header.
        org.mockito.ArgumentCaptor<String> value=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(response).addHeader(eq("Set-Cookie"),value.capture());
        String cookie=value.getValue();
        assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));assertTrue(cookie.contains("Max-Age=0"));
        verify(request,never()).getSession(true);verify(session,never()).invalidate();
    }
    @Test public void logoutRevokesTokenEvenWhenAccessSessionExpired(){
        when(request.getSession(false)).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("mdmesh_web_refresh","credential")});
        assertEquals(200,resource.revoke(request,response,"1").getStatus());verify(dao).revoke("credential");
    }
}
