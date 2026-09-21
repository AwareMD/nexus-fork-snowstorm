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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ValueSet/$validate-code without a value set (no url, id or valueSet) validates the coding
 * against its code system's implicit "all codes" value set. That is the request a validator
 * sends for an unbound CodeableConcept; it used to answer 500.
 */
class FHIRValueSetProviderValidateCodeInferredTest extends AbstractFHIRTest {

	private static final String SYSTEM = "http://example.org/fhir/CodeSystem/colours";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private FHIRCodeSystemVersion codeSystemVersion;

	@BeforeEach
	void testSetup() throws ServiceException {
		CodeSystem codeSystem = fhirJsonParser.parseResource(CodeSystem.class, """
				{
					"resourceType": "CodeSystem",
					"url": "%s",
					"version": "1.0",
					"name": "Colours",
					"status": "active",
					"content": "complete",
					"concept": [
						{ "code": "red", "display": "Red" },
						{ "code": "blue", "display": "Blue" }
					]
				}""".formatted(SYSTEM));
		codeSystemVersion = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), codeSystemVersion);
	}

	@AfterEach
	void testAfter() {
		codeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	private ResponseEntity<String> validate(String parameterJson) {
		HttpEntity<String> request = new HttpEntity<>("""
				{ "resourceType": "Parameters", "parameter": [ %s ] }""".formatted(parameterJson), headers);
		return restTemplate.exchange(baseUrl + "/ValueSet/$validate-code", HttpMethod.POST, request, String.class);
	}

	private Parameters validateOk(String parameterJson) {
		ResponseEntity<String> response = validate(parameterJson);
		expectResponse(response, 200);
		return fhirJsonParser.parseResource(Parameters.class, response.getBody());
	}

	@Test
	void testCodingWithoutValueSet() {
		Parameters p = validateOk("""
				{ "name": "coding", "valueCoding": { "system": "%s", "code": "red" } }""".formatted(SYSTEM));
		assertEquals("true", toString(getProperty(p, "result")));
		assertEquals("Red", toString(getProperty(p, "display")));

		p = validateOk("""
				{ "name": "coding", "valueCoding": { "system": "%s", "code": "green" } }""".formatted(SYSTEM));
		assertEquals("false", toString(getProperty(p, "result")));
	}

	@Test
	void testCodeableConceptWithoutValueSet() {
		Parameters p = validateOk("""
				{ "name": "codeableConcept", "valueCodeableConcept": { "coding": [ { "system": "%s", "code": "blue" } ] } }""".formatted(SYSTEM));
		assertEquals("true", toString(getProperty(p, "result")));
		assertEquals("Blue", toString(getProperty(p, "display")));
	}

	@Test
	void testCodeAndSystemWithoutValueSet() {
		Parameters p = validateOk("""
				{ "name": "code", "valueCode": "red" }, { "name": "system", "valueUri": "%s" }""".formatted(SYSTEM));
		assertEquals("true", toString(getProperty(p, "result")));
	}

	@Test
	void testCodingWithoutSystemOrValueSet() {
		expectResponse(validate("""
				{ "name": "coding", "valueCoding": { "code": "red" } }"""), 400, "No value set was identified");
	}
}
