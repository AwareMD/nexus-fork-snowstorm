package org.snomed.snowstorm.fhir.services;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FHIRConceptMapDeleteTest extends AbstractFHIRTest {

	private static final String SOURCE = "http://example.com/fhir/CodeSystem/del-src";
	private static final String TARGET = "http://example.com/fhir/CodeSystem/del-tgt";

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	@Test
	void deleteByIdRemovesTheHeaderAndItsElements() {
		String url = "http://example.com/fhir/ConceptMap/del-by-id";
		put("del-by-id", url, "1");
		FHIRConceptMap stored = stored("del-by-id").orElseThrow();
		assertEquals(3, elementCount(stored));
		translates(url, "A", 200, "TA");

		assertTrue(delete("/ConceptMap/del-by-id").getStatusCode().is2xxSuccessful());

		assertTrue(stored("del-by-id").isEmpty());
		assertEquals(0, elementCount(stored));
		translates(url, "A", 404, "No suitable map found");
	}

	@Test
	void anIdAddressesExactlyTheStoredResource() {
		// Two versions at one url, under two ids: deleting one leaves the other whole and answering.
		String url = "http://example.com/fhir/ConceptMap/del-versions";
		put("del-versions-1", url, "1");
		put("del-versions-2", url, "2");
		FHIRConceptMap kept = stored("del-versions-2").orElseThrow();

		assertTrue(delete("/ConceptMap/del-versions-1").getStatusCode().is2xxSuccessful());

		List<FHIRConceptMap> remaining = conceptMapRepository.findAllByUrl(url);
		assertEquals(List.of(kept.getId()), remaining.stream().map(FHIRConceptMap::getId).toList());
		assertEquals(3, elementCount(kept));
		translates(url, "A", 200, "TA");
	}

	@Test
	void deleteByUrlAndVersion() {
		String url = "http://example.com/fhir/ConceptMap/del-by-url";
		put("del-by-url", url, "7");
		FHIRConceptMap stored = stored("del-by-url").orElseThrow();

		assertTrue(delete("/ConceptMap?url=" + url + "&version=7").getStatusCode().is2xxSuccessful());

		assertTrue(conceptMapRepository.findAllByUrl(url).isEmpty());
		assertEquals(0, elementCount(stored));
	}

	@Test
	void deletingWhatIsNotStoredAnswers404() {
		assertEquals(HttpStatus.NOT_FOUND, delete("/ConceptMap/del-never-stored").getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND, delete("/ConceptMap?url=http://example.com/fhir/ConceptMap/del-never-stored&version=1").getStatusCode());
	}

	/** The stored header a client calls {@code id}, under that id or, as PUT stored it before, "ConceptMap/" + id. */
	private Optional<FHIRConceptMap> stored(String id) {
		return conceptMapRepository.findById(id).or(() -> conceptMapRepository.findById("ConceptMap/" + id));
	}

	private ResponseEntity<String> delete(String path) {
		return restTemplate.exchange(baseUrl + path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
	}

	private void translates(String url, String code, int status, String expectBodyContains) {
		getParameters(baseUrl + "/ConceptMap/$translate?url=" + url + "&code=" + code + "&system=" + SOURCE + "&targetsystem=" + TARGET,
				status, expectBodyContains);
	}

	private long elementCount(FHIRConceptMap map) {
		List<String> groupIds = map.getGroup().stream().map(FHIRConceptMapGroup::getGroupId).toList();
		return elasticsearchOperations.count(new CriteriaQuery(new Criteria(FHIRMapElement.Fields.GROUP_ID).in(groupIds)), FHIRMapElement.class);
	}

	private void put(String id, String url, String version) {
		String json = "{\"resourceType\":\"ConceptMap\",\"id\":\"" + id + "\",\"url\":\"" + url + "\",\"version\":\"" + version + "\",\"status\":\"active\","
				+ "\"group\":[{\"source\":\"" + SOURCE + "\",\"target\":\"" + TARGET + "\",\"element\":["
				+ "{\"code\":\"A\",\"target\":[{\"code\":\"TA\",\"equivalence\":\"equivalent\"}]},"
				+ "{\"code\":\"B\",\"target\":[{\"code\":\"TB\",\"equivalence\":\"equivalent\"}]},"
				+ "{\"code\":\"C\",\"target\":[{\"code\":\"TC\",\"equivalence\":\"equivalent\"}]}]}]}";
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ConceptMap/" + id, HttpMethod.PUT,
				new HttpEntity<>(json, headers), String.class);
		assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
	}
}
