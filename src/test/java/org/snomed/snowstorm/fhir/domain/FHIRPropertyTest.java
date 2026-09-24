package org.snomed.snowstorm.fhir.domain;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FHIRPropertyTest {

	private static final String OWNING_SYSTEM = "http://example.com/fhir/CodeSystem/owner";

	@Test
	void testCodingPropertyKeepsItsSystemAndVersion() {
		CodeSystem.ConceptPropertyComponent component = new CodeSystem.ConceptPropertyComponent()
				.setCode("dose-form-group")
				.setValue(new Coding("http://example.com/fhir/CodeSystem/dose-form-group", "oral-drops", "oral drops").setVersion("2"));

		Type value = new FHIRProperty(component).toHapiValue(OWNING_SYSTEM);

		Coding coding = assertInstanceOf(Coding.class, value);
		assertEquals("http://example.com/fhir/CodeSystem/dose-form-group", coding.getSystem());
		assertEquals("2", coding.getVersion());
		assertEquals("oral-drops", coding.getCode());
		assertEquals("oral drops", coding.getDisplay());
	}

	@Test
	void testCodingPropertyWithoutSystemFallsBackToTheOwningSystem() {
		// Also the path for a property stored before the system was kept: no system, no version.
		CodeSystem.ConceptPropertyComponent component = new CodeSystem.ConceptPropertyComponent()
				.setCode("parent")
				.setValue(new Coding().setCode("A").setDisplay("Parent A"));

		Coding coding = assertInstanceOf(Coding.class, new FHIRProperty(component).toHapiValue(OWNING_SYSTEM));
		assertEquals(OWNING_SYSTEM, coding.getSystem());
		assertFalse(coding.hasVersion());
		assertEquals("A", coding.getCode());
		assertEquals("Parent A", coding.getDisplay());
	}

	@Test
	void testCodingPropertyStillStoresItsCodeAsTheValue() {
		// Property filters match properties.<code>.value, so the code must stay there.
		CodeSystem.ConceptPropertyComponent component = new CodeSystem.ConceptPropertyComponent()
				.setCode("unit")
				.setValue(new Coding("http://unitsofmeasure.org", "ug", "microgram"));

		assertEquals("ug", new FHIRProperty(component).getValue());
	}
}
