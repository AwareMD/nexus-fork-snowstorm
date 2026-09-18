package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.Test;
import org.hl7.fhir.r4.model.Coding;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import org.snomed.snowstorm.core.data.domain.Concepts;

import static org.hibernate.validator.internal.util.Contracts.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FHIRConceptMapProviderTest extends AbstractFHIRTest {
	
	@Test
	void testHistoricAssociation() {
		String vs = "http://snomed.info/sct?fhir_cm=" + Concepts.REFSET_SAME_AS_ASSOCIATION;
		String url = baseUrl + "/ConceptMap/$translate?code=" + sampleSCTID + "&system=http://snomed.info/sct&url=" + vs;
		Parameters parameters = getParameters(url);
		assertNotNull(parameters);
		assertTrue(parameters.getParameterBool("result"));

		// Use xsct to access daily build
		vs = "http://snomed.info/xsct?fhir_cm=" + Concepts.REFSET_SAME_AS_ASSOCIATION;
		url = baseUrl + "/ConceptMap/$translate?code=" + sampleSCTID + "&system=http://snomed.info/xsct&url=" + vs;
		parameters = getParameters(url);
		assertNotNull(parameters);
		assertTrue(parameters.getParameterBool("result"));

		// Should also work with a specific module
		vs = "http://snomed.info/xsct/1234000008?fhir_cm=" + Concepts.REFSET_SAME_AS_ASSOCIATION;
		url = baseUrl + "/ConceptMap/$translate?code=" + sampleSCTID + "&system=http://snomed.info/sct&url=" + vs;
		parameters = getParameters(url);
		assertNotNull(parameters);
		assertTrue(parameters.getParameterBool("result"));
	}

	@Test
	void testICDMap() {
		String expectBodyContains = "A1.100";
		Parameters parameters = getParameters(baseUrl + "/ConceptMap/$translate?" +
				"code=" + sampleSCTID +
				"&system=http://snomed.info/sct" +
				"&targetsystem=http://hl7.org/fhir/sid/icd-10",
				200, expectBodyContains);
		assertNotNull(parameters);
		assertTrue(parameters.getParameterBool("result"));

		getParameters(baseUrl + "/ConceptMap/$translate?" +
				"code=1000" +
				"&system=http://snomed.info/sct" +
				"&targetsystem=http://hl7.org/fhir/sid/icd-10",
				200, "No mapping found for code");
	}

	@Test
	void testStoredMapTargetCommentIsReturnedAsMessage() {
		// A stored map with two targets for one source, each carrying advice in target.comment,
		// the way a classification map does when the choice depends on context.
		String map = "{\"resourceType\":\"ConceptMap\",\"id\":\"comment-test\","
				+ "\"url\":\"http://example.com/fhir/ConceptMap/comment-test\",\"version\":\"1\",\"status\":\"active\","
				+ "\"sourceUri\":\"http://example.com/fhir/CodeSystem/comment-src\","
				+ "\"targetUri\":\"http://example.com/fhir/CodeSystem/comment-tgt\","
				+ "\"group\":[{\"source\":\"http://example.com/fhir/CodeSystem/comment-src\","
				+ "\"target\":\"http://example.com/fhir/CodeSystem/comment-tgt\",\"element\":[{\"code\":\"S1\","
				+ "\"target\":[{\"code\":\"T1\",\"equivalence\":\"wider\",\"comment\":\"IF FEMALE CHOOSE T1\"},"
				+ "{\"code\":\"T2\",\"equivalence\":\"wider\",\"comment\":\"IF MALE CHOOSE T2\"}]}]}]}";
		HttpEntity<String> request = new HttpEntity<>(map, headers);
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ConceptMap/comment-test", HttpMethod.PUT, request, String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

		Parameters parameters = getParameters(baseUrl + "/ConceptMap/$translate?"
				+ "url=http://example.com/fhir/ConceptMap/comment-test"
				+ "&code=S1&system=http://example.com/fhir/CodeSystem/comment-src"
				+ "&targetsystem=http://example.com/fhir/CodeSystem/comment-tgt", 200, "T2");
		assertNotNull(parameters);
		assertTrue(parameters.getParameterBool("result"));

		// Each advice precedes its own match, in order, so a caller can pair them.
		List<String> sequence = new ArrayList<>();
		for (Parameters.ParametersParameterComponent p : parameters.getParameter()) {
			if ("message".equals(p.getName())) {
				sequence.add("message:" + p.getValue().primitiveValue());
			} else if ("match".equals(p.getName())) {
				Coding concept = (Coding) p.getPart().stream().filter(part -> "concept".equals(part.getName())).findFirst().orElseThrow().getValue();
				sequence.add("match:" + concept.getCode());
			}
		}
		assertEquals(List.of("message:IF FEMALE CHOOSE T1", "match:T1", "message:IF MALE CHOOSE T2", "match:T2"), sequence);
	}

}
