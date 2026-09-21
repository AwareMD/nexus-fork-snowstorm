package org.snomed.snowstorm.fhir.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.fhir.domain.FHIRValueSet;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value set stored without a version sits next to versioned copies of the same url. Finding
 * the latest used to compare the null version and fail.
 */
class FHIRValueSetFinderServiceTest extends AbstractFHIRTest {

	private static final String URL = "http://example.org/fhir/ValueSet/finder-test";

	@Autowired
	private FHIRValueSetFinderService finderService;

	@AfterEach
	void testAfter() {
		valueSetRepository.deleteAll();
	}

	private void store(String id, String version) {
		FHIRValueSet valueSet = new FHIRValueSet();
		valueSet.setId(id);
		valueSet.setUrl(URL);
		valueSet.setVersion(version);
		valueSetRepository.save(valueSet);
	}

	@Test
	void testUnversionedCopyLosesToVersioned() {
		store("finder-test-unversioned", null);
		store("finder-test-1", "1.0.0");

		Optional<FHIRValueSet> found = finderService.find(URL, null);
		assertTrue(found.isPresent());
		assertEquals("1.0.0", found.get().getVersion());

		found = finderService.find(URL, "1.0.0");
		assertTrue(found.isPresent());
		assertEquals("finder-test-1", found.get().getId());
	}

	@Test
	void testOnlyUnversionedCopy() {
		store("finder-test-unversioned", null);

		Optional<FHIRValueSet> found = finderService.find(URL, null);
		assertTrue(found.isPresent());
		assertEquals("finder-test-unversioned", found.get().getId());
	}
}
