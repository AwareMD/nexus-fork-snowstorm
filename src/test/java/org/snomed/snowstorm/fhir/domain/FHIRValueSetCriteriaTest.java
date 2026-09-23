package org.snomed.snowstorm.fhir.domain;

import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FHIRValueSetCriteriaTest {

	private static final String SYSTEM = "http://example.com/fhir/CodeSystem/criteria-test";
	private static final String EXT_URL = "http://example.com/fhir/StructureDefinition/criteria-test";

	@Test
	void getHapiKeepsCodeOrderAndEveryDuplicateReferenceInOrder() {
		// A code listed twice, each time with its own designation and extension, among codes with and without them.
		ValueSet.ConceptSetComponent include = new ValueSet.ConceptSetComponent().setSystem(SYSTEM).setVersion("1");
		include.addConcept().setCode("A").addDesignation(new ValueSet.ConceptReferenceDesignationComponent().setValue("a1"))
				.addExtension(EXT_URL, new StringType("xa1"));
		include.addConcept().setCode("B").addDesignation(new ValueSet.ConceptReferenceDesignationComponent().setValue("b1"));
		include.addConcept().setCode("A").addDesignation(new ValueSet.ConceptReferenceDesignationComponent().setValue("a2"))
				.addExtension(EXT_URL, new StringType("xa2"));
		include.addConcept().setCode("C");

		ValueSet.ConceptSetComponent hapi = new FHIRValueSetCriteria(include).getHapi();

		assertEquals(SYSTEM, hapi.getSystem());
		assertEquals("1", hapi.getVersion());
		// What the per-code scan produced: codes in their listed order, and every occurrence of a
		// duplicated code carrying all of that code's designations and extensions, in listed order.
		assertEquals(List.of("A[a1,a2|xa1,xa2]", "B[b1|]", "A[a1,a2|xa1,xa2]", "C[|]"), render(hapi));
	}

	@Test
	void getHapiOnALargeEnumerationIsLinear() {
		int size = 100_000;
		ValueSet.ConceptSetComponent include = new ValueSet.ConceptSetComponent().setSystem(SYSTEM);
		for (int i = 0; i < size; i++) {
			include.addConcept().setCode("C" + i).addDesignation(new ValueSet.ConceptReferenceDesignationComponent().setValue("d" + i));
		}
		FHIRValueSetCriteria criteria = new FHIRValueSetCriteria(include);

		// A scan of every reference per code is 10^10 comparisons here, minutes of CPU; an index is milliseconds.
		ValueSet.ConceptSetComponent hapi = assertTimeoutPreemptively(Duration.ofSeconds(1), criteria::getHapi);

		assertEquals(size, hapi.getConcept().size());
		for (int i : new int[]{0, 1, size / 2, size - 1}) {
			ValueSet.ConceptReferenceComponent concept = hapi.getConcept().get(i);
			assertEquals("C" + i, concept.getCode());
			assertEquals(1, concept.getDesignation().size());
			assertEquals("d" + i, concept.getDesignationFirstRep().getValue());
		}
	}

	private static List<String> render(ValueSet.ConceptSetComponent hapi) {
		List<String> rendered = new ArrayList<>();
		for (ValueSet.ConceptReferenceComponent concept : hapi.getConcept()) {
			List<String> designations = concept.getDesignation().stream().map(ValueSet.ConceptReferenceDesignationComponent::getValue).toList();
			List<String> extensions = concept.getExtension().stream().map(e -> e.getValue().primitiveValue()).toList();
			rendered.add(concept.getCode() + "[" + String.join(",", designations) + "|" + String.join(",", extensions) + "]");
		}
		return rendered;
	}
}
