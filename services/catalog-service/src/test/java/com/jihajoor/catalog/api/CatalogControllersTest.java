package com.jihajoor.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jihajoor.catalog.api.dto.CategoryView;
import com.jihajoor.catalog.api.dto.SubcategoryView;
import com.jihajoor.catalog.config.CatalogRbacConfig;
import com.jihajoor.catalog.config.WebSecurityConfig;
import com.jihajoor.catalog.domain.ServiceCategory;
import com.jihajoor.catalog.domain.ServiceSubcategory;
import com.jihajoor.catalog.service.CatalogException;
import com.jihajoor.catalog.service.CatalogService;
import com.jihajoor.shared.security.JiHajoorSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** The public catalog and the Admin catalog endpoints through the real security chain. */
@WebMvcTest(properties = "jihajoor.security.jwt-secret=" + CatalogControllersTest.JWT_SECRET)
@ContextConfiguration(classes = {CatalogController.class, AdminCatalogController.class,
        GlobalExceptionHandler.class, WebSecurityConfig.class, CatalogRbacConfig.class})
@ImportAutoConfiguration(JiHajoorSecurityAutoConfiguration.class)
class CatalogControllersTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private static final String CATEGORY_BODY =
            "{\"name\":\"Plumbing\",\"description\":\"Pipes\",\"iconUrl\":null,\"displayOrder\":1}";
    private static final String SUBCATEGORY_BODY =
            "{\"name\":\"Tap repair\",\"basePrice\":299,\"estimatedDurationMin\":45,"
                    + "\"skillTags\":[\"plumbing\"],\"emergencyAvailable\":true}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService catalogService;

    private static String admin() {
        return "Bearer " + Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("ADMIN"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static String customer() {
        return "Bearer " + Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("CUSTOMER"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void anyoneCanBrowseTheActiveCatalog() throws Exception {
        ServiceCategory plumbing = ServiceCategory.create("Plumbing", "Pipes", null, 1);
        ServiceSubcategory tap = ServiceSubcategory.create(plumbing.getId(), "Tap repair",
                new BigDecimal("299.00"), 45, List.of("plumbing"), true);
        when(catalogService.getActiveCatalog()).thenReturn(List.of(
                CategoryView.from(plumbing, List.of(SubcategoryView.from(tap)))));

        mockMvc.perform(get("/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Plumbing"))
                .andExpect(jsonPath("$[0].subcategories[0].name").value("Tap repair"))
                .andExpect(jsonPath("$[0].subcategories[0].emergencyAvailable").value(true));
    }

    @Test
    void anAdminManagesCategories() throws Exception {
        ServiceCategory plumbing = ServiceCategory.create("Plumbing", "Pipes", null, 1);
        UUID id = plumbing.getId();
        when(catalogService.listAllCategories()).thenReturn(List.of(plumbing));
        when(catalogService.createCategory(any())).thenReturn(plumbing);
        when(catalogService.updateCategory(eq(id), any())).thenReturn(plumbing);
        when(catalogService.activateCategory(id)).thenReturn(plumbing);
        when(catalogService.deactivateCategory(id)).thenReturn(plumbing);
        String auth = admin();

        mockMvc.perform(get("/admin/catalog/categories").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
        mockMvc.perform(post("/admin/catalog/categories").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(CATEGORY_BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Plumbing"));
        mockMvc.perform(put("/admin/catalog/categories/" + id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(CATEGORY_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/catalog/categories/" + id + "/activate").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/catalog/categories/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/catalog/categories/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        verify(catalogService).deleteCategory(id);
    }

    @Test
    void anAdminManagesSubcategories() throws Exception {
        UUID categoryId = UUID.randomUUID();
        ServiceSubcategory tap = ServiceSubcategory.create(categoryId, "Tap repair",
                new BigDecimal("299.00"), 45, List.of("plumbing"), false);
        UUID id = tap.getId();
        when(catalogService.listSubcategories(categoryId)).thenReturn(List.of(tap));
        when(catalogService.createSubcategory(eq(categoryId), any())).thenReturn(tap);
        when(catalogService.updateSubcategory(eq(id), any())).thenReturn(tap);
        when(catalogService.activateSubcategory(id)).thenReturn(tap);
        when(catalogService.deactivateSubcategory(id)).thenReturn(tap);
        String auth = admin();

        mockMvc.perform(get("/admin/catalog/categories/" + categoryId + "/subcategories")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].skillTags[0]").value("plumbing"));
        mockMvc.perform(post("/admin/catalog/categories/" + categoryId + "/subcategories")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBCATEGORY_BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.categoryId").value(categoryId.toString()));
        mockMvc.perform(put("/admin/catalog/subcategories/" + id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBCATEGORY_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/catalog/subcategories/" + id + "/activate").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/catalog/subcategories/" + id + "/deactivate").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").exists());
        mockMvc.perform(delete("/admin/catalog/subcategories/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        verify(catalogService).deleteSubcategory(id);
    }

    @Test
    void invalidRequestsAndDomainErrorsUseTheErrorEnvelope() throws Exception {
        UUID missing = UUID.randomUUID();
        when(catalogService.activateCategory(missing))
                .thenThrow(new CatalogException(HttpStatus.CONFLICT, "CATEGORY_HAS_BLOCKERS", "blocked",
                        List.of("2 open bookings")));
        String auth = admin();

        mockMvc.perform(post("/admin/catalog/categories").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"displayOrder\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/admin/catalog/categories/" + missing + "/activate").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_HAS_BLOCKERS"));
        verify(catalogService, never()).createCategory(any());
    }

    @Test
    void onlyAdminsReachTheAdminCatalog() throws Exception {
        mockMvc.perform(get("/admin/catalog/categories").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/catalog/categories")).andExpect(status().isUnauthorized());
        verify(catalogService, never()).listAllCategories();
    }
}
