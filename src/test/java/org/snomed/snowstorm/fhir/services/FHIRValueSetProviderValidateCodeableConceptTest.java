package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.OperationOutcome;
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
 * A CodeableConcept is in a value set when any one of its codings is. The other codings are
 * reported for information at their location, not as a failure, and the coding echoed back is
 * the one that matched.
 */
class FHIRValueSetProviderValidateCodeableConceptTest extends AbstractFHIRTest {

	private static final String COLOURS = "http://example.org/fhir/CodeSystem/colours";
	private static final String SHAPES = "http://example.org/fhir/CodeSystem/shapes";

	// Includes red and blue, not green; shapes are not included at all.
	private static final String VALUE_SET = """
			{ "name": "valueSet", "resource": { "resourceType": "ValueSet", "url": "http://example.org/fhir/ValueSet/primary-colours",
				"compose": { "include": [ { "system": "%s", "concept": [ { "code": "red" }, { "code": "blue" } ] } ] } } }""".formatted(COLOURS);

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private final List<FHIRCodeSystemVersion> codeSystemVersions = new ArrayList<>();

	@BeforeEach
	void testSetup() throws ServiceException {
		store(COLOURS, "Colours", """
				{ "code": "red", "display": "Red" }, { "code": "blue", "display": "Blue" }, { "code": "green", "display": "Green" }""");
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

	private Parameters validate(String... codings) {
		HttpEntity<String> request = new HttpEntity<>("""
				{ "resourceType": "Parameters", "parameter": [ %s,
					{ "name": "codeableConcept", "valueCodeableConcept": { "coding": [ %s ] } } ] }""".formatted(VALUE_SET, String.join(", ", codings)), headers);
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ValueSet/$validate-code", HttpMethod.POST, request, String.class);
		expectResponse(response, 200);
		return fhirJsonParser.parseResource(Parameters.class, response.getBody());
	}

	private static List<OperationOutcome.OperationOutcomeIssueComponent> issues(Parameters p) {
		Parameters.ParametersParameterComponent issues = p.getParameter("issues");
		return issues == null ? List.of() : ((OperationOutcome) issues.getResource()).getIssue();
	}

	private static void assertMatched(Parameters p, String code, String display) {
		assertEquals("true", toStringValue(p, "result"));
		assertEquals(code, toStringValue(p, "code"));
		assertEquals(COLOURS, toStringValue(p, "system"));
		assertEquals(display, toStringValue(p, "display"));
		assertNull(p.getParameter("message"));
		assertTrue(issues(p).stream().noneMatch(i -> i.getSeverity() == OperationOutcome.IssueSeverity.ERROR), "no error issues");
	}

	private static String toStringValue(Parameters p, String name) {
		Parameters.ParametersParameterComponent parameter = p.getParameter(name);
		return parameter == null ? null : parameter.getValue().primitiveValue();
	}

	@Test
	void testMemberThenForeignSystem() {
		Parameters p = validate(coding(COLOURS, "red"), coding(SHAPES, "square"));
		assertMatched(p, "red", "Red");
		OperationOutcome.OperationOutcomeIssueComponent notInVs = issues(p).stream()
				.filter(i -> i.getLocation().stream().anyMatch(l -> l.getValue().equals("CodeableConcept.coding[1].code")))
				.findFirst().orElseThrow();
		assertEquals(OperationOutcome.IssueSeverity.INFORMATION, notInVs.getSeverity());
		assertEquals("this-code-not-in-vs", notInVs.getDetails().getCodingFirstRep().getCode());
	}

	@Test
	void testForeignSystemThenMember() {
		assertMatched(validate(coding(SHAPES, "square"), coding(COLOURS, "red")), "red", "Red");
	}

	@Test
	void testCodeOfIncludedSystemNotInValueSetThenMember() {
		assertMatched(validate(coding(COLOURS, "green"), coding(COLOURS, "blue")), "blue", "Blue");
	}

	@Test
	void testNoCodingInValueSet() {
		Parameters p = validate(coding(SHAPES, "square"), coding(COLOURS, "green"));
		assertEquals("false", toStringValue(p, "result"));
		assertTrue(toStringValue(p, "message").contains("No valid coding was found"), toStringValue(p, "message"));
		assertTrue(issues(p).stream().anyMatch(i -> i.getSeverity() == OperationOutcome.IssueSeverity.ERROR), "an error issue");
	}
}
