package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.ConceptMap;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.fhir.domain.FHIRConceptMap;
import org.snomed.snowstorm.fhir.domain.FHIRConceptMapGroup;
import org.snomed.snowstorm.fhir.domain.FHIRMapElement;
import org.snomed.snowstorm.fhir.repositories.FHIRMapElementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FHIRConceptMapReadTest extends AbstractFHIRTest {

	private static final String SOURCE = "http://example.com/fhir/CodeSystem/read-src";
	private static final String TARGET = "http://example.com/fhir/CodeSystem/read-tgt";

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	@Autowired
	private FHIRMapElementRepository mapElementRepository;

	@Autowired
	private FHIRConceptMapProvider provider;

	@Test
	void aMapStoredThroughPutIsReadByItsId() {
		String url = "http://example.com/fhir/ConceptMap/read-put";
		put("read-put", url, "1", "TA");

		ConceptMap read = read("read-put");
		assertEquals("read-put", read.getIdElement().getIdPart());
		assertEquals(url, read.getUrl());
		assertEquals(3, read.getGroupFirstRep().getElement().size());
		assertEquals(List.of("read-put"), conceptMapRepository.findAllByUrl(url).stream().map(FHIRConceptMap::getId).toList());
	}

	@Test
	void aMapStoredUnderTheTypePrefixedIdIsReadByItsId() {
		// How PUT stored every map before: under the id HAPI hands over, "ConceptMap/<id>".
		String url = "http://example.com/fhir/ConceptMap/read-legacy";
		seedLegacy("read-legacy", url, "1");

		ConceptMap read = read("read-legacy");
		assertEquals("read-legacy", read.getIdElement().getIdPart());
		assertEquals(3, read.getGroupFirstRep().getElement().size());
		assertEquals("read-legacy", searchedId(url));
	}

	@Test
	void rePuttingAPrefixedMapReplacesIt() {
		String url = "http://example.com/fhir/ConceptMap/read-reput";
		FHIRConceptMap legacy = seedLegacy("read-reput", url, "1");
		translates(url, "A", 200, "TA");

		put("read-reput", url, "1", "TA2");

		List<FHIRConceptMap> stored = conceptMapRepository.findAllByUrl(url);
		assertEquals(List.of("read-reput"), stored.stream().map(FHIRConceptMap::getId).toList());
		assertEquals(0, elementCount(legacy));
		assertEquals(3, elementCount(stored.getFirst()));
		translates(url, "A", 200, "TA2");
		assertEquals("read-reput", read("read-reput").getIdElement().getIdPart());
	}

	@Test
	void anIdHoldsOneMapWhateverItsVersion() {
		// PUT to an id replaces what is stored there, even at another version.
		String url = "http://example.com/fhir/ConceptMap/read-reversion";
		FHIRConceptMap legacy = seedLegacy("read-reversion", url, "1");

		put("read-reversion", url, "2", "TA2");

		List<FHIRConceptMap> stored = conceptMapRepository.findAllByUrl(url);
		assertEquals(List.of("read-reversion|2"), stored.stream().map(map -> map.getId() + "|" + map.getVersion()).toList());
		assertEquals(0, elementCount(legacy));
		assertEquals(3, elementCount(stored.getFirst()));

		// And an id stored bare, re-PUT at another version, does not leave its elements behind.
		FHIRConceptMap second = stored.getFirst();
		put("read-reversion", url, "3", "TA3");
		assertEquals(0, elementCount(second));
		assertEquals(List.of("read-reversion|3"), conceptMapRepository.findAllByUrl(url).stream().map(map -> map.getId() + "|" + map.getVersion()).toList());
	}

	@Test
	void anUnknownIdIsNotFound() {
		assertEquals(HttpStatus.NOT_FOUND, get("/ConceptMap/read-never-stored").getStatusCode());
	}

	@Test
	void readOnlyModeRefusesPutAndStillReads() {
		String url = "http://example.com/fhir/ConceptMap/read-readonly";
		put("read-readonly", url, "1", "TA");
		ReflectionTestUtils.setField(provider, "readOnlyMode", true);
		try {
			ResponseEntity<String> refused = restTemplate.exchange(baseUrl + "/ConceptMap/read-readonly", HttpMethod.PUT,
					new HttpEntity<>(json("read-readonly", url, "2", "TA2"), headers), String.class);
			assertEquals(HttpStatus.UNAUTHORIZED, refused.getStatusCode());
			assertEquals("1", read("read-readonly").getVersion());
		} finally {
			ReflectionTestUtils.setField(provider, "readOnlyMode", false);
		}
	}

	private ConceptMap read(String id) {
		ResponseEntity<String> response = get("/ConceptMap/" + id);
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
		return fhirJsonParser.parseResource(ConceptMap.class, response.getBody());
	}

	private String searchedId(String url) {
		ResponseEntity<String> response = get("/ConceptMap?url=" + url);
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
		org.hl7.fhir.r4.model.Bundle bundle = fhirJsonParser.parseResource(org.hl7.fhir.r4.model.Bundle.class, response.getBody());
		assertEquals(1, bundle.getEntry().size());
		return bundle.getEntryFirstRep().getResource().getIdElement().getIdPart();
	}

	private ResponseEntity<String> get(String path) {
		return restTemplate.exchange(baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	/** A map stored as PUT stored it before: header and elements, the header under "ConceptMap/" + id. */
	private FHIRConceptMap seedLegacy(String id, String url, String version) {
		FHIRConceptMap map = new FHIRConceptMap(fhirJsonParser.parseResource(ConceptMap.class, json(id, url, version, "TA")));
		map.setId("ConceptMap/" + id);
		for (FHIRConceptMapGroup group : map.getGroup()) {
			mapElementRepository.saveAll(group.getElement());
		}
		conceptMapRepository.save(map);
		assertTrue(conceptMapRepository.findById("ConceptMap/" + id).isPresent());
		assertTrue(conceptMapRepository.findById(id).isEmpty());
		assertEquals(3, elementCount(map));
		return map;
	}

	private void translates(String url, String code, int status, String expectBodyContains) {
		getParameters(baseUrl + "/ConceptMap/$translate?url=" + url + "&code=" + code + "&system=" + SOURCE + "&targetsystem=" + TARGET,
				status, expectBodyContains);
	}

	private long elementCount(FHIRConceptMap map) {
		List<String> groupIds = map.getGroup().stream().map(FHIRConceptMapGroup::getGroupId).toList();
		return elasticsearchOperations.count(new CriteriaQuery(new Criteria(FHIRMapElement.Fields.GROUP_ID).in(groupIds)), FHIRMapElement.class);
	}

	private void put(String id, String url, String version, String targetOfA) {
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ConceptMap/" + id, HttpMethod.PUT,
				new HttpEntity<>(json(id, url, version, targetOfA), headers), String.class);
		assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
	}

	private static String json(String id, String url, String version, String targetOfA) {
		return "{\"resourceType\":\"ConceptMap\",\"id\":\"" + id + "\",\"url\":\"" + url + "\",\"version\":\"" + version + "\",\"status\":\"active\","
				+ "\"group\":[{\"source\":\"" + SOURCE + "\",\"target\":\"" + TARGET + "\",\"element\":["
				+ "{\"code\":\"A\",\"target\":[{\"code\":\"" + targetOfA + "\",\"equivalence\":\"equivalent\"}]},"
				+ "{\"code\":\"B\",\"target\":[{\"code\":\"TB\",\"equivalence\":\"equivalent\"}]},"
				+ "{\"code\":\"C\",\"target\":[{\"code\":\"TC\",\"equivalence\":\"equivalent\"}]}]}]}";
	}
}
