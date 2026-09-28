package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A CodeSystem id addresses exactly one stored code system version.
 *
 * The id field is mapped as analyzed text, so a derived query on it matched every document whose
 * id contained the requested id's tokens, and the highest version won. With "loinc-2.83-2.83"
 * and "nexus-loinc-fr-pclocd-20260331.loinc-2.83" both stored, GET and DELETE of the first
 * answered with, and deleted, the second.
 */
class FHIRCodeSystemExactIdTest extends AbstractFHIRTest {

	private static final String SHORT_URL = "http://example.com/fhir/CodeSystem/exact-id-short";
	private static final String LONG_URL = "http://example.com/fhir/CodeSystem/exact-id-long";

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private FHIRCodeSystemVersion shortVersion;
	private FHIRCodeSystemVersion longVersion;

	@BeforeEach
	void store() throws ServiceException {
		// Every token of the first id is also a token of the second, whose version sorts higher.
		shortVersion = codeSystemService.createUpdate(codeSystem("loinc-2.83", SHORT_URL, "2.83"));
		longVersion = codeSystemService.createUpdate(codeSystem("nexus-loinc-fr", LONG_URL, "pclocd-20260331.loinc-2.83"));
		assertEquals("loinc-2.83-2.83", shortVersion.getId());
		assertEquals("nexus-loinc-fr-pclocd-20260331.loinc-2.83", longVersion.getId());
	}

	@AfterEach
	void remove() {
		codeSystemRepository.findById(shortVersion.getId()).ifPresent(codeSystemService::deleteCodeSystemVersion);
		codeSystemRepository.findById(longVersion.getId()).ifPresent(codeSystemService::deleteCodeSystemVersion);
	}

	@Test
	void readByIdAnswersWithThatCodeSystemOnly() {
		ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + "/CodeSystem/loinc-2.83-2.83", String.class);
		expectResponse(response, 200);
		CodeSystem read = fhirJsonParser.parseResource(CodeSystem.class, response.getBody());
		assertEquals(SHORT_URL, read.getUrl());
		assertEquals("2.83", read.getVersion());
	}

	@Test
	void deleteByIdRemovesThatCodeSystemOnly() {
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/CodeSystem/loinc-2.83-2.83", HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
		assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
		assertTrue(codeSystemRepository.findById("loinc-2.83-2.83").isEmpty());
		assertTrue(codeSystemRepository.findById("nexus-loinc-fr-pclocd-20260331.loinc-2.83").isPresent());
	}

	@Test
	void anIdThatIsOnlyAPrefixOfTokensFindsNothing() {
		ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + "/CodeSystem/loinc-2.83", String.class);
		assertEquals(404, response.getStatusCode().value(), response.getBody());
	}

	private CodeSystem codeSystem(String id, String url, String version) {
		CodeSystem cs = new CodeSystem();
		cs.setId(id);
		cs.setUrl(url);
		cs.setVersion(version);
		cs.setName("ExactIdTest");
		cs.setStatus(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.DRAFT);
		cs.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
		return cs;
	}
}
