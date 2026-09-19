package com.memory.xzp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    private CorsConfiguration configuration() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String setting = yaml.getObject().getProperty("app.cors.allowed-origins");
        String defaults = setting.substring(setting.indexOf(':') + 1, setting.length() - 1);
        var config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", defaults);
        var registry = new CorsRegistry() {
            CorsConfiguration result() { return getCorsConfigurations().get("/**"); }
        };
        config.addCorsMappings(registry);
        return registry.result();
    }

    @Test void credentialedLoginPostAndPreflightAcceptTheConfiguredFrontend() throws Exception {
        for (String method : new String[]{"POST", "OPTIONS"}) {
            var request = new MockHttpServletRequest(method, "/auth/accountLogin");
            request.setServerName("127.0.0.1"); request.setServerPort(8088);
            request.addHeader("Origin", "http://127.0.0.1:8080");
            if (method.equals("OPTIONS")) {
                request.addHeader("Access-Control-Request-Method", "POST");
                request.addHeader("Access-Control-Request-Headers", "content-type");
            }
            var response = new MockHttpServletResponse();
            assertTrue(new DefaultCorsProcessor().processRequest(configuration(), request, response));
            assertEquals("http://127.0.0.1:8080", response.getHeader("Access-Control-Allow-Origin"));
            assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
        }
    }

    @Test void portDriftAndUntrustedOriginsRemainRejected() throws Exception {
        for (String origin : new String[]{"http://127.0.0.1:8081", "https://untrusted.example"}) {
            var request = new MockHttpServletRequest("POST", "/auth/accountLogin");
            request.addHeader("Origin", origin);
            var response = new MockHttpServletResponse();
            assertFalse(new DefaultCorsProcessor().processRequest(configuration(), request, response));
            assertEquals(403, response.getStatus());
            assertEquals("Invalid CORS request", response.getContentAsString());
        }
    }
}
