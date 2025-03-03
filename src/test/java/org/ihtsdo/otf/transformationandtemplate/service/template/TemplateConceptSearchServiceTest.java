package org.ihtsdo.otf.transformationandtemplate.service.template;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FileUtils;
import org.ihtsdo.otf.rest.client.terminologyserver.SnowstormRestClient;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.ConceptPojo;
import org.ihtsdo.otf.rest.client.terminologyserver.pojo.RelationshipPojo;
import org.ihtsdo.otf.transformationandtemplate.service.AbstractServiceTest;
import org.ihtsdo.otf.transformationandtemplate.service.JsonStore;
import org.ihtsdo.otf.transformationandtemplate.service.TestDataHelper;
import org.ihtsdo.otf.transformationandtemplate.service.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;
import org.snomed.authoringtemplate.domain.ConceptTemplate;
import org.snomed.authoringtemplate.domain.logical.Attribute;
import org.snomed.authoringtemplate.domain.logical.AttributeGroup;
import org.snomed.authoringtemplate.domain.logical.LogicalTemplate;
import org.snomed.authoringtemplate.service.LogicalTemplateParserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.when;

public class TemplateConceptSearchServiceTest extends AbstractServiceTest {

	private static final String TEMPLATES = "/templates/";

	private static final String JSON = ".json";

	@Autowired
	private TemplateConceptSearchService searchService;
	
	@MockBean
	private TemplateService templateService;
	
	@Autowired
	private JsonStore templateJsonStore;
	
	private LogicalTemplateParserService logicalTemplateParser;

	private Gson gson;
	
	private String templateName;
	
	@BeforeEach
	public void setUp() {
		logicalTemplateParser = new LogicalTemplateParserService();
		gson = new GsonBuilder().setPrettyPrinting().create();
	}
	
	@Test
	public void searchConceptsLogicallyAndLexically() throws ServiceException, IOException, URISyntaxException {
		String templateName = "CT guided [procedure] of [body structure]";
		setUpTemplate(templateName);
		Set<String> concepts = searchService.searchConceptsByTemplate(templateName, "test", true, true, true);
		assertNotNull(concepts);
		assertTrue(concepts.isEmpty());
	}
	
	@Test
	public void searchConceptsLogicallyWithoutMatchingResults() throws Exception {
		String templateName = "CT guided [procedure] of [body structure]";
		setUpTemplate(templateName);
		ConceptPojo testConcept = TestDataHelper.createCTGuidedProcedureConcept(true);
		RelationshipPojo additionalRel = new RelationshipPojo(2, "246075003", "6543217", TestDataHelper.STATED_RELATIONSHIP);
		testConcept.getClassAxioms().iterator().next().getRelationships().add(additionalRel);
		when(terminologyServerClient.eclQuery(anyString(), anyString(), anyInt(), anyBoolean()))
				.thenReturn(Collections.singleton(testConcept.getConceptId()));
		
		when(terminologyServerClient.searchConcepts(anyString(), anyList()))
				.thenReturn(Collections.singletonList(testConcept));
		
		Set<String> concepts = searchService.searchConceptsByTemplate(templateName, "test", true, null, true);
		assertNotNull(concepts);
		assertEquals(0, concepts.size());
	}
		
	private ConceptTemplate setUpTemplate(String templateName) throws IOException, URISyntaxException {
		FileUtils.copyFileToDirectory(new File(getClass().getResource(TEMPLATES + templateName + JSON).toURI()), templateJsonStore.getStoreDirectory());
		ConceptTemplate template = templateJsonStore.load(templateName, ConceptTemplate.class);
		when(templateService.loadOrThrow(anyString()))
			.thenReturn(template);
		expectGetTerminologyServerClient();
		return template;
	}
	
	@Test
	public void searchConceptsLogicallyWithResults() throws Exception {
		String templateName = "CT guided [procedure] of [body structure]";
		setUpTemplate(templateName);
		ConceptPojo testConcept = TestDataHelper.createCTGuidedProcedureConcept(true);
		when(terminologyServerClient.eclQuery(anyString(), anyString(), anyInt(), anyBoolean()))
			.thenReturn(new HashSet<>(Arrays.asList(testConcept.getConceptId())));
		
		when(terminologyServerClient.searchConcepts(anyString(), anyList()))
			.thenReturn(Arrays.asList(testConcept));
		
		Set<String> concepts = searchService.searchConceptsByTemplate(templateName, "test", true, null, true);
		assertNotNull(concepts);
		assertEquals(1, concepts.size());
	}
	
	@Test
	public void searchConceptsLogicallyWithoutOptionalAttributeType() throws Exception {
		String templateName = "CT guided [procedure] of [body structure]";
		setUpTemplate(templateName);
		
		ConceptPojo testConcept = TestDataHelper.createCTGuidedProcedureConcept(false);
		when(terminologyServerClient.eclQuery(anyString(), anyString(), anyInt(), anyBoolean()))
			.thenReturn(Collections.singleton(testConcept.getConceptId()));
		
		when(terminologyServerClient.searchConcepts(anyString(), anyList()))
				.thenReturn(Collections.singletonList(testConcept));
		
		Set<String> concepts = searchService.searchConceptsByTemplate(templateName, "test", true, null, true);
		assertNotNull(concepts);
		assertEquals(1, concepts.size());
	}
	
	private OngoingStubbing<SnowstormRestClient> expectGetTerminologyServerClient() {
		return when(clientFactory.getClient()).thenReturn(terminologyServerClient);
	}
		
	@Test
	public void testConstructEclQuery() throws IOException, ServiceException {
		String logical = "420134006 |Propensity to adverse reactions (disorder)|:\n" + 
				"	\n" + 
				"	370135005 |Pathological process (attribute)| = 472964009 |Allergic process (qualifier value)|,\n" + 
				"	{\n" + 
				"		246075003 |Causative agent (attribute)| = [[+id(<105590001 |Substance (substance)|) @substance]],\n" + 
				"		255234002 |After (attribute)| = 609327009 |Allergic sensitization (disorder)|\n" + 
				"	}";
		LogicalTemplate logicalTemplate = logicalTemplateParser.parseTemplate(logical);
		List<String> focusConcepts = logicalTemplate.getFocusConcepts();
		List<AttributeGroup> attributeGroups = logicalTemplate.getAttributeGroups();
		List<Attribute> unGroupedAttriburtes = logicalTemplate.getUngroupedAttributes();
		String ecl = searchService.constructEclQuery(focusConcepts, attributeGroups, unGroupedAttriburtes);
		String expected = "<<420134006:370135005=472964009,{246075003=<105590001 |Substance (substance)|,255234002=609327009}";
		assertEquals(expected, ecl);
	}
	
	
	@Test
	public void testConstructEclQueryWithCardinality() throws IOException, ServiceException {
		String logical = "71388002 |Procedure|:\n\t[[~1..1]] {\n\t\t260686004 |Method| = 312251004 |Computed tomography imaging action|,\n\t\t[[~1..1]] "
				+ "405813007 |Procedure site - Direct| = [[+id(<< 442083009 |Anatomical or acquired body structure|) @procSite]]\n\t}\n";
		LogicalTemplate logicalTemplate = logicalTemplateParser.parseTemplate(logical);
		List<String> focusConcepts = logicalTemplate.getFocusConcepts();
		List<AttributeGroup> attributeGroups = logicalTemplate.getAttributeGroups();
		List<Attribute> unGroupedAttriburtes = logicalTemplate.getUngroupedAttributes();
		String ecl = searchService.constructEclQuery(focusConcepts, attributeGroups, unGroupedAttriburtes);
		String expected = "<<71388002:[1..1]{260686004=312251004,[1..1]405813007=<< 442083009 |Anatomical or acquired body structure|}";
		assertEquals(expected, ecl);
	}
	
	
	@Test
	public void testConstructEclQueryWithSlotReference() throws IOException, ServiceException {
		String logical = "71388002 |Procedure|:   [[~1..1]] {      260686004 |Method| = 312251004 |Computed tomography imaging action|, "
				+ "     [[~1..1]] 405813007 |Procedure site - Direct| = [[+id(<< 442083009 |Anatomical or acquired body structure|) @procSite]],"
				+ "      363703001 |Has intent| = 429892002 |Guidance intent|   },   "
				+ "{      260686004 |Method| = [[+id (<< 129264002 |Action|) @action]],      "
				+ "[[~1..1]] 405813007 |Procedure site - Direct| = [[+id $procSite]]   }";
				 
		LogicalTemplate logicalTemplate = logicalTemplateParser.parseTemplate(logical);
		List<String> focusConcepts = logicalTemplate.getFocusConcepts();
		List<AttributeGroup> attributeGroups = logicalTemplate.getAttributeGroups();
		List<Attribute> unGroupedAttriburtes = logicalTemplate.getUngroupedAttributes();
		String ecl = searchService.constructEclQuery(focusConcepts, attributeGroups, unGroupedAttriburtes);
		System.out.println(ecl);
		String expected = "<<71388002:[1..1]{260686004=312251004,[1..1]405813007=<< 442083009 |Anatomical or acquired body structure|,363703001=429892002},"
				+ "{260686004=<< 129264002 |Action|,[1..1]405813007=<< 442083009 |Anatomical or acquired body structure|}";
		assertEquals(expected, ecl);
		
	}
	
	@Test
	public void testConstructEclQueryWithCompoundAttributeRange() throws Exception {
		templateName = "LOINC Template - Process Observable - 100 - 2";
		ConceptTemplate template = setUpTemplate(templateName);
		LogicalTemplate logicalTemplate = logicalTemplateParser.parseTemplate(template.getLogicalTemplate());
		String ecl = searchService.constructEclQuery(logicalTemplate.getFocusConcepts(), logicalTemplate.getAttributeGroups(), logicalTemplate.getUngroupedAttributes());
		String expected ="<<363787002:704321009=<<719982003 |Process|,"
				+ "704324001=(<<105590001 |Substance| OR <<719982003 |Process|),"
				+ "704322002=(<<123037004 |Body structure| OR <<410607006 |Organism| OR <<260787004 |Physical object| OR <<373873005 |Pharmaceutical / biologic product|),"
				+ "704323007=<7389001 |Time frame|,370130000=<<118598001 |Measurement property|,"
				+ "704327008=(<<123037004 |Body structure| OR <<410607006 |Organism| OR <<105590001 |Substance| OR <<123038009 |Specimen| OR <<260787004 |Physical object| "
				+ "OR <<373873005 |Pharmaceutical / biologic product| OR <<419891008 |Record artifact|),370132008=(<< 30766002 |Quantitative| OR << 26716007 |Qualitative| "
				+ "OR << 117363000 |Ordinal value| OR << 117365007 |Ordinal or quantitative value| OR << 117362005 |Nominal value| OR << 117364006 |Narrative value| OR << 117444000 |Text value|)";
		assertEquals(expected, ecl);
	}
	
	
	@Test
	public void testFindConceptsWithExactMatch() throws Exception {
		String templateName = "Allergy to [substance] V2";
		ConceptTemplate template = setUpTemplate(templateName);
		LogicalTemplate logicalTemplate = logicalTemplateParser.parseTemplate(template.getLogicalTemplate());
		ConceptPojo concept = gson.fromJson(new InputStreamReader(getClass().getResourceAsStream("Allergy_to_Aluminium_With_Axiom.json")), ConceptPojo.class);
		Set<String> result = searchService.findConceptsNotMatchExactly(Collections.singletonList(concept), logicalTemplate.getAttributeGroups(), logicalTemplate.getUngroupedAttributes(), true);
		assertTrue(result.isEmpty());
	}
}
	
	
