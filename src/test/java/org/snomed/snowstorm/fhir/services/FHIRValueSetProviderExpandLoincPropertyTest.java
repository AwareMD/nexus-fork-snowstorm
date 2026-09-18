package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOINC loaded as an ordinary FHIR CodeSystem carries its properties (SCALE_TYP, CLASS,
 * SYSTEM, STATUS...) exactly as any other code system does, and the base FHIR spec's own
 * LOINC value sets filter on them. The LOINC-specific filter handler used to refuse every
 * property but parent and ancestor, on a server that had the data.
 */
class FHIRValueSetProviderExpandLoincPropertyTest extends AbstractFHIRTest {

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
					"url": "http://loinc.org",
					"version": "2.82-test",
					"name": "LOINC",
					"status": "active",
					"content": "fragment",
					"property": [
						{ "code": "SCALE_TYP", "type": "string" },
						{ "code": "CLASS", "type": "string" }
					],
					"concept": [
						{ "code": "2345-7", "display": "Glucose [Mass/volume] in Serum or Plasma",
						  "property": [ { "code": "SCALE_TYP", "valueString": "Qn" }, { "code": "CLASS", "valueString": "CHEM" } ] },
						{ "code": "5778-6", "display": "Color of Urine",
						  "property": [ { "code": "SCALE_TYP", "valueString": "Nom" }, { "code": "CLASS", "valueString": "UA" } ] },
						{ "code": "5767-9", "display": "Appearance of Urine",
						  "property": [ { "code": "SCALE_TYP", "valueString": "Nom" }, { "code": "CLASS", "valueString": "UA" } ] },
						{ "code": "2947-0", "display": "Sodium [Moles/volume] in Blood",
						  "property": [ { "code": "SCALE_TYP", "valueString": "Qn" }, { "code": "CLASS", "valueString": "CHEM" } ] }
					]
				}""");
		codeSystemVersion = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), codeSystemVersion);
	}

	@AfterEach
	void testAfter() {
		codeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	private ValueSet expand(String filterJson) {
		HttpEntity<String> request = new HttpEntity<>("""
				{
					"resourceType": "Parameters",
					"parameter": [ { "name": "valueSet", "resource": {
						"resourceType": "ValueSet",
						"compose": { "include": [ { "system": "http://loinc.org", "filter": [ %s ] } ] }
					} } ]
				}""".formatted(filterJson), headers);
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ValueSet/$expand", HttpMethod.POST, request, String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
		return fhirJsonParser.parseResource(ValueSet.class, response.getBody());
	}

	private static List<String> codes(ValueSet valueSet) {
		return valueSet.getExpansion().getContains().stream().map(ValueSet.ValueSetExpansionContainsComponent::getCode).sorted().collect(Collectors.toList());
	}

	@Test
	void testScaleTypeEquals() {
		assertEquals(List.of("5767-9", "5778-6"), codes(expand("""
				{ "property": "SCALE_TYP", "op": "=", "value": "Nom" }""")));
	}

	@Test
	void testClassIn() {
		assertEquals(List.of("2345-7", "2947-0"), codes(expand("""
				{ "property": "CLASS", "op": "in", "value": "CHEM,HEM/BC" }""")));
	}

	@Test
	void testNotIn() {
		assertEquals(List.of("5767-9", "5778-6"), codes(expand("""
				{ "property": "CLASS", "op": "not-in", "value": "CHEM" }""")));
	}
}
