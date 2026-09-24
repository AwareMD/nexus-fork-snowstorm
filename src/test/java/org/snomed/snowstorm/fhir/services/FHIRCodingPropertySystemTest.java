package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A concept property whose value is a Coding names its own system, which is usually not the code
 * system that carries the property: a dose form drawn from a dose-form code system, a unit from
 * UCUM. $lookup and $expand must return that system, not the url of the code system being asked.
 */
class FHIRCodingPropertySystemTest extends AbstractFHIRTest {

	private static final String CS_URL = "http://example.com/fhir/CodeSystem/coding-property-test";
	private static final String DOSE_FORM_SYSTEM = "http://example.com/fhir/CodeSystem/dose-form-group";
	private static final String UCUM = "http://unitsofmeasure.org";

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
					"version": "1",
					"name": "CodingPropertyTest",
					"status": "draft",
					"content": "complete",
					"property": [
						{ "code": "dose-form-group", "type": "Coding" },
						{ "code": "unit", "type": "Coding" },
						{ "code": "local-group", "type": "Coding" }
					],
					"concept": [
						{
							"code": "80134385",
							"display": "Product in oral drops",
							"property": [
								{ "code": "dose-form-group", "valueCoding": { "system": "%s", "version": "2", "code": "oral-drops", "display": "oral drops" } },
								{ "code": "unit", "valueCoding": { "system": "%s", "code": "ug", "display": "microgram" } },
								{ "code": "local-group", "valueCoding": { "code": "B", "display": "Group B" } }
							]
						},
						{
							"code": "80134386",
							"display": "Product in tablets",
							"property": [
								{ "code": "dose-form-group", "valueCoding": { "system": "%s", "version": "2", "code": "tablet", "display": "tablet" } }
							]
						},
						{ "code": "B", "display": "Group B" }
					]
				}""".formatted(CS_URL, DOSE_FORM_SYSTEM, UCUM, DOSE_FORM_SYSTEM));
		codeSystemVersion = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), codeSystemVersion);
	}

	@AfterEach
	void testAfter() {
		codeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	private Coding lookupCoding(String code, String property) {
		Parameters parameters = getParameters(baseUrl + "/CodeSystem/$lookup?system=" + CS_URL + "&code=" + code);
		return assertInstanceOf(Coding.class, getProperty(parameters, property), property + " must come back as a Coding");
	}

	private ValueSet expand(String includeFilter, String extraParameter) {
		HttpEntity<String> request = new HttpEntity<>("""
				{
					"resourceType": "Parameters",
					"parameter": [
						{ "name": "valueSet", "resource": {
							"resourceType": "ValueSet",
							"compose": { "include": [ { "system": "%s" %s } ] }
						} }
						%s
					]
				}""".formatted(CS_URL, includeFilter, extraParameter), headers);
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ValueSet/$expand", HttpMethod.POST, request, String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
		return fhirJsonParser.parseResource(ValueSet.class, response.getBody());
	}

	@Test
	void testLookupReturnsTheCodingsOwnSystemAndVersion() {
		Coding coding = lookupCoding("80134385", "dose-form-group");
		assertEquals(DOSE_FORM_SYSTEM, coding.getSystem());
		assertEquals("2", coding.getVersion());
		assertEquals("oral-drops", coding.getCode());
		assertEquals("oral drops", coding.getDisplay());
	}

	@Test
	void testLookupReturnsAUcumUnitUnderUcum() {
		Coding coding = lookupCoding("80134385", "unit");
		assertEquals(UCUM, coding.getSystem());
		assertEquals("ug", coding.getCode());
	}

	@Test
	void testLookupOfACodingWithNoSystemStillAnswers() {
		// No system to keep: the owning code system is named, as before.
		Coding coding = lookupCoding("80134385", "local-group");
		assertEquals(CS_URL, coding.getSystem());
		assertEquals("B", coding.getCode());
		assertEquals("Group B", coding.getDisplay());
	}

	@Test
	void testExpandPropertyReturnsTheCodingsOwnSystem() {
		ValueSet valueSet = expand("", """
				, { "name": "property", "valueCode": "dose-form-group" }""");
		ValueSet.ValueSetExpansionContainsComponent contains = valueSet.getExpansion().getContains().stream()
				.filter(c -> c.getCode().equals("80134385")).findFirst().orElseThrow();
		Extension property = contains.getExtension().stream()
				.filter(e -> e.hasExtension("code") && "dose-form-group".equals(e.getExtensionByUrl("code").getValue().primitiveValue()))
				.findFirst().orElseThrow(() -> new AssertionError("no dose-form-group property on the expansion"));
		Coding coding = assertInstanceOf(Coding.class, property.getExtensionByUrl("value").getValue());
		assertEquals(DOSE_FORM_SYSTEM, coding.getSystem());
		assertEquals("2", coding.getVersion());
		assertEquals("oral-drops", coding.getCode());
	}

	@Test
	void testFilterOnACodingPropertyStillMatchesItsCode() {
		ValueSet valueSet = expand("""
				, "filter": [ { "property": "dose-form-group", "op": "=", "value": "oral-drops" } ]""", "");
		assertEquals(List.of("80134385"), valueSet.getExpansion().getContains().stream()
				.map(ValueSet.ValueSetExpansionContainsComponent::getCode).toList());
	}
}
