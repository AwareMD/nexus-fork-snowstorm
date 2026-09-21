package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeSystem/$validate-code takes a codeableConcept, as ValueSet/$validate-code does: the
 * concept is valid when any of its codings is a code of the code system. A validator sends
 * this shape for every CodeableConcept without a binding; the parameter used to be dropped
 * and the request refused for naming no code system.
 */
class FHIRCodeSystemProviderValidateCodeableConceptTest extends AbstractFHIRTest {

	private static final String COLOURS = "http://example.org/fhir/CodeSystem/colours";
	private static final String SHAPES = "http://example.org/fhir/CodeSystem/shapes";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private final List<FHIRCodeSystemVersion> codeSystemVersions = new ArrayList<>();

	@BeforeEach
	void testSetup() throws ServiceException {
		store(COLOURS, "Colours", """
				{ "code": "red", "display": "Red" }, { "code": "blue", "display": "Blue" }""");
		store(SHAPES, "Shapes", """
				{ "code": "square", "display": "Square" }""");
	}

	private void store(String url, String name, String conceptsJson) throws ServiceException {
		CodeSystem codeSystem = fhirJsonParser.parseResource(CodeSystem.class, """
				{ "resourceType": "CodeSystem", "url": "%s", "version": "1.0", "name": "%s", "status": "active", "content": "complete",
					"concept": [ %s ] }""".formatted(url, name, conceptsJson));
		FHIRCodeSystemVersion version = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), version);
		codeSystemVersions.add(version);
	}

	@AfterEach
	void testAfter() {
		codeSystemVersions.forEach(codeSystemService::deleteCodeSystemVersion);
	}

	private static String coding(String system, String code) {
		return """
				{ "system": "%s", "code": "%s" }""".formatted(system, code);
	}

	private Parameters validate(String path, String url, String... codings) {
		String urlParameter = url == null ? "" : """
				{ "name": "url", "valueUri": "%s" },""".formatted(url);
		HttpEntity<String> request = new HttpEntity<>("""
				{ "resourceType": "Parameters", "parameter": [ %s
					{ "name": "codeableConcept", "valueCodeableConcept": { "coding": [ %s ] } } ] }""".formatted(urlParameter, String.join(", ", codings)), headers);
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
		expectResponse(response, 200);
		return fhirJsonParser.parseResource(Parameters.class, response.getBody());
	}

	private static String value(Parameters p, String name) {
		Parameters.ParametersParameterComponent parameter = p.getParameter(name);
		return parameter == null ? null : parameter.getValue().primitiveValue();
	}

	@Test
	void testNoUrlEachCodingOwnSystem() {
		Parameters p = validate("/CodeSystem/$validate-code", null, coding(SHAPES, "square"), coding(COLOURS, "red"));
		assertEquals("true", value(p, "result"));
		assertEquals("square", value(p, "code"));
		assertEquals(SHAPES, value(p, "system"));
		assertEquals("Square", value(p, "display"));
		assertNotNull(p.getParameter("codeableConcept"));
	}

	@Test
	void testUrlSelectsTheCodingOfThatSystem() {
		Parameters p = validate("/CodeSystem/$validate-code", COLOURS, coding(SHAPES, "square"), coding(COLOURS, "red"));
		assertEquals("true", value(p, "result"));
		assertEquals("red", value(p, "code"));
		assertEquals(COLOURS, value(p, "system"));
	}

	@Test
	void testAnyCodingUnknownCodeThenKnown() {
		Parameters p = validate("/CodeSystem/$validate-code", COLOURS, coding(COLOURS, "green"), coding(COLOURS, "blue"));
		assertEquals("true", value(p, "result"));
		assertEquals("blue", value(p, "code"));
	}

	@Test
	void testNoCodingIsValid() {
		Parameters p = validate("/CodeSystem/$validate-code", COLOURS, coding(COLOURS, "green"));
		assertEquals("false", value(p, "result"));

		p = validate("/CodeSystem/$validate-code", COLOURS, coding(SHAPES, "square"));
		assertEquals("false", value(p, "result"));
		assertTrue(value(p, "message").contains("No coding of the codeableConcept is from the code system"), value(p, "message"));
	}

	@Test
	void testInstanceLevel() {
		String id = codeSystemVersions.get(0).getId();
		Parameters p = validate("/CodeSystem/" + id + "/$validate-code", null, coding(SHAPES, "square"), coding(COLOURS, "red"));
		assertEquals("true", value(p, "result"));
		assertEquals("red", value(p, "code"));
		assertEquals(COLOURS, value(p, "system"));
	}
}
