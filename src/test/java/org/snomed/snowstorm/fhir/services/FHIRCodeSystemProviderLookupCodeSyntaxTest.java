package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Codes are opaque strings. Some real ones (HL7 v2 table 0210 and v3 RelationshipConjunction
 * have AND, OR and NOT) spell query-parser operators, and a lookup that hands the code to a
 * query parser answers 500 instead of the concept. The punctuation cases pin down that a code
 * is matched whole, whatever it contains.
 */
class FHIRCodeSystemProviderLookupCodeSyntaxTest extends AbstractFHIRTest {

	private static final String SYSTEM = "http://example.org/fhir/CodeSystem/conjunction";

	@Autowired
	private FHIRConceptService conceptService;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	private FHIRCodeSystemVersion codeSystemVersion;

	@BeforeEach
	void testSetup() throws ServiceException {
		CodeSystem codeSystem = fhirJsonParser.parseResource(CodeSystem.class, """
				{
					"resourceType": "CodeSystem",
					"url": "%s",
					"version": "1.0",
					"name": "Conjunction",
					"status": "active",
					"content": "complete",
					"concept": [
						{ "code": "AND", "display": "and" },
						{ "code": "OR", "display": "or" },
						{ "code": "NOT", "display": "not" },
						{ "code": "a:b", "display": "colon" },
						{ "code": "1*", "display": "asterisk" }
					]
				}""".formatted(SYSTEM));
		codeSystemVersion = codeSystemService.createUpdate(codeSystem);
		conceptService.saveAllConceptsOfCodeSystemVersion(codeSystem.getConcept(), codeSystemVersion);
	}

	@AfterEach
	void testAfter() {
		codeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	private String lookupDisplay(String code) {
		Parameters p = getParameters(baseUrl + "/CodeSystem/$lookup?system=" + SYSTEM + "&code=" + code);
		return toString(getProperty(p, "display"));
	}

	@Test
	void testLookupOperatorWords() {
		assertEquals("and", lookupDisplay("AND"));
		assertEquals("or", lookupDisplay("OR"));
		assertEquals("not", lookupDisplay("NOT"));
	}

	@Test
	void testLookupPunctuation() {
		assertEquals("colon", lookupDisplay("a:b"));
		assertEquals("asterisk", lookupDisplay("1*"));
	}

	@Test
	void testLookupUnknownCodeIsNotFound() {
		getParameters(baseUrl + "/CodeSystem/$lookup?system=" + SYSTEM + "&code=XOR", 404, "not found");
	}

	@Test
	void testValidateCodeOperatorWord() {
		Parameters p = getParameters(baseUrl + "/CodeSystem/$validate-code?url=" + SYSTEM + "&code=AND");
		assertEquals("true", toString(getProperty(p, "result")));
		assertEquals("and", toString(getProperty(p, "display")));
	}
}
