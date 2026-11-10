package com.genersoft.iot.vmp.vmanager.user;

import com.genersoft.iot.vmp.service.IRoleService;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.Role;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileRoleTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void profileReloadsTheFullCurrentRoleInsteadOfReturningOnlyItsId() {
        User principal = new User();principal.setUsername("profile-test");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
        var users = mock(IUserService.class);var roles = mock(IRoleService.class);
        User stored = new User();stored.setUsername("profile-test");
        Role reference = new Role();reference.setId(1);stored.setRole(reference);
        when(users.getUser("profile-test", null)).thenReturn(stored);
        Role complete = new Role();complete.setId(1);complete.setAuthority("0");complete.setName("admin");
        when(roles.getRoleById(1)).thenReturn(complete);
        var controller = new UserController();ReflectionTestUtils.setField(controller,"userService",users);ReflectionTestUtils.setField(controller,"roleService",roles);
        var profile = controller.getUserInfo();
        assertSame(complete, profile.getRole());assertEquals("0", profile.getRole().getAuthority());
        verify(roles).getRoleById(1);
    }

    @Test void aDeletedRoleDoesNotReceiveAnAdministrativeFallback() {
        User principal = new User();principal.setUsername("profile-test");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
        var users = mock(IUserService.class);var roles = mock(IRoleService.class);
        User stored = new User();stored.setUsername("profile-test");Role reference = new Role();reference.setId(7);stored.setRole(reference);
        when(users.getUser("profile-test", null)).thenReturn(stored);
        var controller = new UserController();ReflectionTestUtils.setField(controller,"userService",users);ReflectionTestUtils.setField(controller,"roleService",roles);
        assertNull(controller.getUserInfo().getRole());
    }
}
