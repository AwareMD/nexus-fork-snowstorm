package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.fhir.domain.FHIRConceptMap;
import org.snomed.snowstorm.fhir.domain.FHIRConceptMapGroup;
import org.snomed.snowstorm.fhir.domain.FHIRMapElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FHIRConceptMapBulkWriteTest extends AbstractFHIRTest {

	private static final String SOURCE = "http://example.com/fhir/CodeSystem/bulk-src";
	private static final String TARGET = "http://example.com/fhir/CodeSystem/bulk-tgt";

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	@Test
	void aMapLargerThanOneBatchIsStoredWhole() {
		String url = "http://example.com/fhir/ConceptMap/bulk-large";
		int size = 12_000; // more than two batches
		put("bulk-large", mapJson("bulk-large", url, size, -1));

		List<FHIRConceptMap> stored = conceptMapRepository.findAllByUrl(url);
		assertEquals(1, stored.size());
		assertEquals(size, elementCount(stored.getFirst()));
		assertTranslates(url, "bulk-large-S0", "T0");
		assertTranslates(url, "bulk-large-S" + (size - 1), "T" + (size - 1));

		// Replacing it leaves one copy, and removes the elements of the copy it replaced.
		FHIRConceptMap first = stored.getFirst();
		put("bulk-large", mapJson("bulk-large", url, size, -1));
		stored = conceptMapRepository.findAllByUrl(url);
		assertEquals(1, stored.size());
		assertEquals(size, elementCount(stored.getFirst()));
		assertEquals(0, elementCount(first), "the replaced copy's elements are left behind");
	}

	@Test
	void aFailedElementWriteLeavesThePreviousMapAnswering() {
		String url = "http://example.com/fhir/ConceptMap/bulk-fail";
		put("bulk-fail", mapJson("bulk-fail", url, 10, -1));
		FHIRConceptMap previous = conceptMapRepository.findAllByUrl(url).getFirst();

		// The replacement's element 5,500, in its second batch, cannot be indexed: a keyword over
		// Lucene's 32,766-byte term limit. The first batch has already been written when it fails.
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ConceptMap/bulk-fail", HttpMethod.PUT,
				new HttpEntity<>(mapJson("bulk-fail", url, 6_000, 5_500), headers), String.class);
		assertTrue(response.getStatusCode().isError(), response.getStatusCode().toString());

		List<FHIRConceptMap> stored = conceptMapRepository.findAllByUrl(url);
		assertEquals(1, stored.size());
		assertEquals(previous.getGroup().getFirst().getGroupId(), stored.getFirst().getGroup().getFirst().getGroupId());
		assertEquals(10, elementCount(stored.getFirst()));
		assertTranslates(url, "bulk-fail-S9", "T9");
		// Nothing of the failed write is left in the element index: its first batch was removed.
		assertEquals(0, elementsWithCode("bulk-fail-S4999"));
	}

	@Test
	void aFailedFirstWriteLeavesNoMap() {
		String url = "http://example.com/fhir/ConceptMap/bulk-fail-new";
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ConceptMap/bulk-fail-new", HttpMethod.PUT,
				new HttpEntity<>(mapJson("bulk-fail-new", url, 6_000, 5_500), headers), String.class);
		assertTrue(response.getStatusCode().isError(), response.getStatusCode().toString());

		// Absent, so $translate says there is no such map, rather than present and answering "no mapping".
		assertTrue(conceptMapRepository.findAllByUrl(url).isEmpty());
		getParameters(baseUrl + "/ConceptMap/$translate?url=" + url + "&code=bulk-fail-new-S0&system=" + SOURCE, 404, "No suitable map found");
	}

	private long elementsWithCode(String code) {
		return elasticsearchOperations.count(new CriteriaQuery(new Criteria(FHIRMapElement.Fields.CODE).is(code)), FHIRMapElement.class);
	}

	private long elementCount(FHIRConceptMap map) {
		// A count, not a page's total: a search stops counting at 10,000.
		List<String> groupIds = map.getGroup().stream().map(FHIRConceptMapGroup::getGroupId).toList();
		return elasticsearchOperations.count(new CriteriaQuery(new Criteria(FHIRMapElement.Fields.GROUP_ID).in(groupIds)), FHIRMapElement.class);
	}

	private void put(String id, String json) {
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ConceptMap/" + id, HttpMethod.PUT,
				new HttpEntity<>(json, headers), String.class);
		assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
	}

	private void assertTranslates(String url, String code, String expectedTarget) {
		Parameters parameters = getParameters(baseUrl + "/ConceptMap/$translate?url=" + url + "&code=" + code
				+ "&system=" + SOURCE + "&targetsystem=" + TARGET, 200, expectedTarget);
		assertTrue(parameters.getParameterBool("result"), code);
	}

	/** A one-group map of {@code size} elements id-S<i> -> T<i>; element {@code badIndex}, if any, has an unindexable code. */
	private static String mapJson(String id, String url, int size, int badIndex) {
		StringBuilder elements = new StringBuilder();
		for (int i = 0; i < size; i++) {
			if (i > 0) {
				elements.append(',');
			}
			String code = i == badIndex ? "X".repeat(40_000) : id + "-S" + i;
			elements.append("{\"code\":\"").append(code).append("\",\"target\":[{\"code\":\"T").append(i).append("\",\"equivalence\":\"equivalent\"}]}");
		}
		return "{\"resourceType\":\"ConceptMap\",\"id\":\"" + id + "\",\"url\":\"" + url + "\",\"version\":\"1\",\"status\":\"active\","
				+ "\"group\":[{\"source\":\"" + SOURCE + "\",\"target\":\"" + TARGET + "\",\"element\":[" + elements + "]}]}";
	}
}
