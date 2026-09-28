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

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Several include (or exclude) clauses over ONE code system each stand alone: a code is in the
 * expansion when any include selects it and no exclude does. Only the filters within one clause
 * are ANDed. Clauses were grouped by code system version, so two includes over one system were
 * ANDed together -- disjoint includes expanded to nothing -- and two excludes removed only the
 * codes both of them matched.
 */
class FHIRValueSetClauseUnionTest extends AbstractFHIRTest {

	private static final String CS_URL = "http://example.com/fhir/CodeSystem/clause-union-test";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private FHIRCodeSystemVersion codeSystemVersion;

	@BeforeEach
	void store() throws ServiceException {
		CodeSystem codeSystem = fhirJsonParser.parseResource(CodeSystem.class, """
				{
					"resourceType": "CodeSystem",
					"url": "%s",
					"version": "1",
					"name": "ClauseUnionTest",
					"status": "draft",
					"content": "complete",
					"property": [ { "code": "grp", "type": "string" } ],
					"concept": [
						{ "code": "A", "display": "A", "property": [ { "code": "grp", "valueString": "x" } ] },
						{ "code": "B", "display": "B", "property": [ { "code": "grp", "valueString": "y" } ] },
						{ "code": "C", "display": "C", "property": [ { "code": "grp", "valueString": "z" } ] },
						{ "code": "D", "display": "D", "property": [ { "code": "grp", "valueString": "x" } ] }
					]
				}""".formatted(CS_URL));
		codeSystemVersion = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), codeSystemVersion);
	}

	@AfterEach
	void remove() {
		codeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	@Test
	void twoIncludesOfCodesAreAUnion() {
		assertEquals(Set.of("A", "B"), expand("""
				"include": [ %s, %s ]""".formatted(concepts("A"), concepts("B"))));
	}

	@Test
	void twoIncludesOfFiltersAreAUnion() {
		assertEquals(Set.of("A", "B", "D"), expand("""
				"include": [ %s, %s ]""".formatted(grp("x"), grp("y"))));
	}

	@Test
	void anIncludeOfCodesAndAnIncludeOfAFilterAreAUnion() {
		assertEquals(Set.of("A", "B"), expand("""
				"include": [ %s, %s ]""".formatted(concepts("A"), grp("y"))));
	}

	@Test
	void filtersWithinOneIncludeStillIntersect() {
		assertEquals(Set.of("A"), expand("""
				"include": [ { "system": "%s", "concept": [ { "code": "A" }, { "code": "B" } ], "filter": [ { "property": "grp", "op": "=", "value": "x" } ] } ]""".formatted(CS_URL)));
	}

	@Test
	void eachExcludeOfCodesRemovesItsOwnCodes() {
		assertEquals(Set.of("C", "D"), expand("""
				"include": [ { "system": "%s" } ], "exclude": [ %s, %s ]""".formatted(CS_URL, concepts("A"), concepts("B"))));
	}

	@Test
	void eachExcludeOfAFilterRemovesItsOwnCodes() {
		assertEquals(Set.of("C"), expand("""
				"include": [ { "system": "%s" } ], "exclude": [ %s, %s ]""".formatted(CS_URL, grp("x"), grp("y"))));
	}

	private String concepts(String code) {
		return """
				{ "system": "%s", "concept": [ { "code": "%s" } ] }""".formatted(CS_URL, code);
	}

	private String grp(String value) {
		return """
				{ "system": "%s", "filter": [ { "property": "grp", "op": "=", "value": "%s" } ] }""".formatted(CS_URL, value);
	}

	private Set<String> expand(String compose) {
		HttpEntity<String> request = new HttpEntity<>("""
				{
					"resourceType": "Parameters",
					"parameter": [ { "name": "valueSet", "resource": { "resourceType": "ValueSet", "compose": { %s } } } ]
				}""".formatted(compose), headers);
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ValueSet/$expand", HttpMethod.POST, request, String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
		ValueSet valueSet = fhirJsonParser.parseResource(ValueSet.class, response.getBody());
		return valueSet.getExpansion().getContains().stream().map(ValueSet.ValueSetExpansionContainsComponent::getCode)
				.collect(Collectors.toCollection(TreeSet::new));
	}
}
