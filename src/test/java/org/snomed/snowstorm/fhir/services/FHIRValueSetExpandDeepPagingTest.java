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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Paging a non-SNOMED expansion by offset and count has to visit every code exactly once.
 *
 * It did not past offset 10,000. Pages below that were cut from a query sorted by display length
 * then code; pages above it were cut from a search_after walk sorted by code alone. Two different
 * orders meet at the boundary, so a client paging to the end received some codes twice and never
 * received others, while every page reported the right total.
 *
 * The second code system repeats codes of the first with identical displays, so the sort also has
 * to break a tie between two concepts with the same display length and the same code.
 */
class FHIRValueSetExpandDeepPagingTest extends AbstractFHIRTest {

	/** Past the 10,000 at which the expansion switches from from/size to search_after. */
	private static final int FIRST_SYSTEM_CONCEPTS = 10_500;
	/** Codes shared with the first system, so code alone does not order the expansion. */
	private static final int SECOND_SYSTEM_CONCEPTS = 1_000;
	private static final int TOTAL = FIRST_SYSTEM_CONCEPTS + SECOND_SYSTEM_CONCEPTS;

	private static final String SYSTEM_A = "http://deep-paging-test/cs-a";
	private static final String SYSTEM_B = "http://deep-paging-test/cs-b";
	private static final String VS_URL = "http://deep-paging-test/vs";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private final List<FHIRCodeSystemVersion> versions = new ArrayList<>();

	@BeforeEach
	void setup() throws ServiceException {
		versions.add(storeCodeSystem(SYSTEM_A, FIRST_SYSTEM_CONCEPTS));
		versions.add(storeCodeSystem(SYSTEM_B, SECOND_SYSTEM_CONCEPTS));
		HttpEntity<String> request = new HttpEntity<>(format("""
				{"resourceType": "ValueSet", "url": "%s", "version": "1", "status": "draft",
				 "compose": {"include": [{"system": "%s"}, {"system": "%s"}]}}""", VS_URL, SYSTEM_A, SYSTEM_B), headers);
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ValueSet", HttpMethod.POST, request, String.class);
		assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
	}

	@AfterEach
	void tearDown() {
		restTemplate.exchange(baseUrl + "/ValueSet?url=" + VS_URL + "&version=1", HttpMethod.DELETE, null, String.class);
		versions.forEach(codeSystemService::deleteCodeSystemVersion);
	}

	@Test
	void pagingPastTenThousandVisitsEveryCodeOnce() {
		assertPagesCoverExpansion(VS_URL, TOTAL, 1_000);
		assertPagesCoverExpansion(VS_URL, TOTAL, 5_000);
	}

	@Test
	void pagingAWholeCodeSystemPastTenThousandVisitsEveryCodeOnce() {
		// The implicit value set of every code in one system takes the same path.
		assertPagesCoverExpansion(SYSTEM_A + "?fhir_vs", FIRST_SYSTEM_CONCEPTS, 1_000);
	}

	@Test
	void aPageAcrossTheBoundaryMatchesTheSamePageOfTheWholeExpansion() {
		// One request for everything is a single search_after walk, so it is the order every page must agree with.
		List<String> whole = page(VS_URL, TOTAL, 0, TOTAL);
		assertEquals(TOTAL, whole.size());
		assertEquals(whole.subList(9_000, 10_000), page(VS_URL, TOTAL, 9_000, 1_000));
		assertEquals(whole.subList(9_500, 10_000), page(VS_URL, TOTAL, 9_500, 500));
		assertEquals(whole.subList(10_000, 11_000), page(VS_URL, TOTAL, 10_000, 1_000));
		assertEquals(whole.subList(0, 1_000), page(VS_URL, TOTAL, 0, 1_000));
	}

	private void assertPagesCoverExpansion(String url, int total, int count) {
		List<String> returned = new ArrayList<>();
		for (int offset = 0; offset < total; offset += count) {
			List<String> page = page(url, total, offset, count);
			assertFalse(page.isEmpty(), "Empty page at offset " + offset);
			returned.addAll(page);
		}
		Set<String> distinct = new HashSet<>(returned);
		assertEquals(total, returned.size(), url + " codes returned with count=" + count);
		assertEquals(total, distinct.size(), url + " distinct codes returned with count=" + count);
	}

	private List<String> page(String url, int total, int offset, int count) {
		ResponseEntity<String> response = restTemplate.exchange(
				format("%s/ValueSet/$expand?url=%s&offset=%d&count=%d", baseUrl, url, offset, count), HttpMethod.GET, null, String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
		ValueSet expanded = fhirJsonParser.parseResource(ValueSet.class, response.getBody());
		assertEquals(total, expanded.getExpansion().getTotal());
		List<String> codes = new ArrayList<>();
		for (ValueSet.ValueSetExpansionContainsComponent contains : expanded.getExpansion().getContains()) {
			codes.add(contains.getSystem() + "|" + contains.getCode());
		}
		return codes;
	}

	private FHIRCodeSystemVersion storeCodeSystem(String url, int concepts) throws ServiceException {
		CodeSystem codeSystem = new CodeSystem();
		codeSystem.setUrl(url);
		codeSystem.setVersion("1");
		codeSystem.setStatus(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.DRAFT);
		codeSystem.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
		for (int i = 0; i < concepts; i++) {
			// Display length cycles independently of the code, so display-length order and code order disagree.
			codeSystem.addConcept().setCode(format("c%05d", i)).setDisplay("d".repeat(1 + (i * 7919) % 61));
		}
		FHIRCodeSystemVersion version = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), version);
		return version;
	}
}
