package com.genersoft.iot.vmp.conf.security;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class LogAuthenticationTest {
    JwtAuthenticationFilter filter;IUserService users;UserSetting settings;
    @BeforeEach void setup(){filter=new JwtAuthenticationFilter();users=mock(IUserService.class);settings=new UserSetting();settings.setInterfaceAuthentication(false);ReflectionTestUtils.setField(filter,"userService",users);ReflectionTestUtils.setField(filter,"userSetting",settings);SecurityContextHolder.clearContext();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void anonymousLogAndAutoConfigurationRemainProtectedWhenOtherApiAuthIsDisabled() throws Exception {
        for(String path:new String[]{"/channel/log","/api/common/channel/auto-play"}){
            MockHttpServletRequest request=new MockHttpServletRequest("GET",path);MockHttpServletResponse response=new MockHttpServletResponse();
            var chain=mock(jakarta.servlet.FilterChain.class);filter.doFilter(request,response,chain);assertEquals(401,response.getStatus());verifyNoInteractions(chain);
        }
    }
    @Test void validAdminWebSocketTokenIsForwardedAndExpiredTokenIsRejected() throws Exception {
        JwtUser jwt=new JwtUser();jwt.setStatus(JwtUser.TokenStatus.NORMAL);jwt.setUserId(1);jwt.setUserName("test-admin");jwt.setRoleId(1);
        Role role=new Role();role.setAuthority("0");User user=new User();user.setId(1);user.setRole(role);when(users.getUserById(1)).thenReturn(user);
        try(MockedStatic<JwtUtils> tokens=mockStatic(JwtUtils.class)){
            tokens.when(JwtUtils::getHeader).thenReturn("access-token");tokens.when(JwtUtils::getApiKeyHeader).thenReturn("key");tokens.when(()->JwtUtils.verifyToken("test-token")).thenReturn(jwt);
            var request=new MockHttpServletRequest("GET","/channel/log");request.addHeader("sec-websocket-protocol","test-token");var response=new MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
            filter.doFilter(request,response,chain);verify(chain).doFilter(any(),eq(response));assertEquals("test-token",response.getHeader("sec-websocket-protocol"));
            SecurityContextHolder.clearContext();jwt.setStatus(JwtUser.TokenStatus.EXPIRED);
            var expired=new MockHttpServletRequest("GET","/channel/log");expired.addHeader("sec-websocket-protocol","test-token");var denied=new MockHttpServletResponse();var noChain=mock(jakarta.servlet.FilterChain.class);
            filter.doFilter(expired,denied,noChain);assertEquals(401,denied.getStatus());verifyNoInteractions(noChain);
        }
    }
    @Test void viewerCannotSubscribeToOperationsLogs() throws Exception {
        JwtUser jwt=new JwtUser();jwt.setStatus(JwtUser.TokenStatus.NORMAL);jwt.setUserId(2);
        Role role=new Role();role.setAuthority("2");User user=new User();user.setId(2);user.setRole(role);when(users.getUserById(2)).thenReturn(user);
        try(MockedStatic<JwtUtils> tokens=mockStatic(JwtUtils.class)){
            tokens.when(JwtUtils::getHeader).thenReturn("access-token");tokens.when(()->JwtUtils.verifyToken("viewer-token")).thenReturn(jwt);
            var request=new MockHttpServletRequest("GET","/channel/log");request.addHeader("sec-websocket-protocol","viewer-token");var response=new MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
            filter.doFilter(request,response,chain);assertEquals(403,response.getStatus());verifyNoInteractions(chain);
        }
    }
}
