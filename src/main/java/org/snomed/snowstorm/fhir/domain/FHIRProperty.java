package org.snomed.snowstorm.fhir.domain;

import ca.uhn.fhir.jpa.entity.TermConceptProperty;
import org.hl7.fhir.common.hapi.validation.util.TermConceptPropertyTypeEnum;
import org.hl7.fhir.r4.model.*;

import java.util.Arrays;

public class FHIRProperty {

	public static final String STRING_TYPE = "STRING";
	public static final String CODING_TYPE = "CODING";
	public static final String CODE_TYPE = "CODE";
	public static final String BOOLEAN_TYPE = "BOOLEAN";
	public static final String INTEGER_TYPE = "INTEGER";
	public static final String DECIMAL_TYPE = "DECIMAL";
	public static final String ID_TYPE = "ID";

	protected static final String[] URLS = {"http://hl7.org/fhir/StructureDefinition/itemWeight",
			"http://hl7.org/fhir/StructureDefinition/codesystem-label",
			"http://hl7.org/fhir/StructureDefinition/codesystem-conceptOrder"};

	private String code;
	private String display;
	private String value;
	private String type;
	private String systemVersionUrl;
	// The system and version of a Coding value. A Coding-valued property names its own system,
	// which is usually not the code system carrying the property (a unit from UCUM, a dose form
	// from a dose-form code system). Absent on properties stored before they were kept, and on
	// Codings that give no system; the owning code system is returned for those, as before.
	private String system;
	private String version;

	public FHIRProperty() {
	}

	public FHIRProperty(String code, String display, String value, String type) {
		this.code = code;
		this.display = display;
		this.value = value;
		this.type = type;
	}

	public FHIRProperty(Coding coding) {
		code = coding.getCode();
		display = coding.getDisplay();
		type = CODING_TYPE;
		if (coding.hasSystem()) {
			systemVersionUrl = coding.getSystem();
		}
	}

	public FHIRProperty(TermConceptProperty property) {
		code = property.getKey();
		display = property.getDisplay();
		value = property.getValue();
		TermConceptPropertyTypeEnum enumType = property.getType();
		type = enumType != null ? enumType.name() : CODING_TYPE;
	}

	public FHIRProperty(CodeSystem.ConceptPropertyComponent propertyComponent) {
		code = propertyComponent.getCode();
		if (propertyComponent.hasValueCoding()) {
			Coding valueCoding = propertyComponent.getValueCoding();
			value = valueCoding.getCode();
			display = valueCoding.getDisplay();
			system = valueCoding.getSystem();
			version = valueCoding.getVersion();
			type = CODING_TYPE;
		} else if (propertyComponent.hasValueCodeType()) {
			value = propertyComponent.getValueCodeType().getValue();
			type = CODE_TYPE;
		} else if (propertyComponent.hasValueStringType()) {
			value = propertyComponent.getValueStringType().getValue();
			type = STRING_TYPE;
		} else if (propertyComponent.hasValueBooleanType()){
			value = propertyComponent.getValueBooleanType().getValueAsString();
			type = BOOLEAN_TYPE;
		} else if (propertyComponent.hasValueIntegerType()){
			value = propertyComponent.getValueIntegerType().getValueAsString();
			type = INTEGER_TYPE;
		} else if (propertyComponent.hasValueDecimalType()){
			value = propertyComponent.getValueDecimalType().getValueAsString();
			type = DECIMAL_TYPE;
		}
	}

	public static String typeToFHIRPropertyType(Type value) {
		String fhirPropertyType;
		if (value instanceof CodeType) {
			fhirPropertyType = CODE_TYPE;
		} else if (value instanceof StringType){
			fhirPropertyType = STRING_TYPE;
		} else if (value instanceof Coding) {
			fhirPropertyType = CODING_TYPE;
		} else if (value instanceof BooleanType) {
			fhirPropertyType = BOOLEAN_TYPE;
		} else if (value instanceof IntegerType) {
			fhirPropertyType = INTEGER_TYPE;
		} else if (value instanceof DecimalType) {
			fhirPropertyType = DECIMAL_TYPE;
		} else if (value instanceof IdType) {
			fhirPropertyType = ID_TYPE;
		} else {
			throw new IllegalArgumentException("Unknown FHIRProperty type: " + value.getClass().getName());
		}
		return fhirPropertyType;
	}

	public Type toHapiValue(String systemVersionUrl) {
		if (STRING_TYPE.equals(type)) {
			return new StringType(value);
		} else if (CODE_TYPE.equals(type)) {
			return new CodeType(value);
		} else if (CODING_TYPE.equals(type)) {
			Coding coding = new Coding(system != null ? system : systemVersionUrl, value, display);
			return version != null ? coding.setVersion(version) : coding;
		} else if (BOOLEAN_TYPE.equals(type)) {
			return new BooleanType(value);
		} else if (INTEGER_TYPE.equals(type)) {
			return new IntegerType(value);
		}else if (DECIMAL_TYPE.equals(type)) {
			return new DecimalType(value);
		}
		return null;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getDisplay() {
		return display;
	}

	public void setDisplay(String display) {
		this.display = display;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public boolean isSpecialExtension() {
		return Arrays.asList(URLS).contains(code);
	}

	public String getSystemVersionUrl() { return systemVersionUrl; }

	public void setSystemVersionUrl(String systemVersionUrl) { this.systemVersionUrl = systemVersionUrl; }

	public String getSystem() { return system; }

	public void setSystem(String system) { this.system = system; }

	public String getVersion() { return version; }

	public void setVersion(String version) { this.version = version; }
}
