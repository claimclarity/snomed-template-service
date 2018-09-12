package org.ihtsdo.otf.authoringtemplate.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import javax.annotation.PostConstruct;

import org.ihtsdo.otf.authoringtemplate.service.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.authoringtemplate.domain.ConceptMini;
import org.snomed.authoringtemplate.domain.ConceptTemplate;
import org.snomed.authoringtemplate.domain.Concepts;
import org.snomed.authoringtemplate.domain.Description;
import org.snomed.authoringtemplate.domain.LexicalTemplate;
import org.snomed.authoringtemplate.domain.Relationship;
import org.snomed.authoringtemplate.domain.SimpleSlot;
import org.snomed.authoringtemplate.domain.logical.Attribute;
import org.snomed.authoringtemplate.domain.logical.AttributeGroup;
import org.snomed.authoringtemplate.domain.logical.LogicalTemplate;
import org.snomed.authoringtemplate.service.LogicalTemplateParserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TemplateStore {

	private Map<String, ConceptTemplate> templateCache;

	@Autowired
	private LogicalTemplateParserService logicalParserService;

	@Autowired
	private JsonStore jsonStore;

	private Logger logger = LoggerFactory.getLogger(getClass());

	public TemplateStore() throws IOException {
		templateCache = new HashMap<>();
	}

	/**
	 * Init method loads all templates from disk into cache.
	 * Call again if the templates on disk are changed from outside this application.
	 * @throws IOException
	 */
	@PostConstruct
	public void init() throws IOException, ServiceException {
		logger.info("Loading templates into cache.");
		templateCache.clear();
		Set<ConceptTemplate> conceptTemplates = jsonStore.loadAll(ConceptTemplate.class);
		for (ConceptTemplate template : conceptTemplates) {
			generateAndCache(template);
		}
		logger.info("{} templates loaded into cache.", templateCache.size());
	}

	private void generateAndCache(ConceptTemplate template) throws ServiceException {
		try {
			generateTemporalParts(template);
			templateCache.put(encodeSlash(template.getName()), template);
		} catch (Exception e) {
			String errorMsg = String.format("Failed to load template %s", template.getName());
			logger.error(errorMsg, e);
			throw new ServiceException(errorMsg, e);
		}
	}

	private String encodeSlash(String name) {
		return name.replace("/", "%2F");
	}

	public ConceptTemplate load(String name) throws IOException {
		return templateCache.get(name);
	}

	public Set<ConceptTemplate> loadAll() throws IOException {
		return new HashSet<>(templateCache.values());
	}

	public void save(String name, ConceptTemplate conceptTemplate) throws IOException, ServiceException {
		conceptTemplate.setName(name);
		stripTemporalParts(conceptTemplate);
		jsonStore.save(name, conceptTemplate);
		generateAndCache(conceptTemplate);
	}

	private void stripTemporalParts(ConceptTemplate conceptTemplate) {
		conceptTemplate.setFocusConcept(null);
		conceptTemplate.getConceptOutline().getRelationships().clear();
		conceptTemplate.getConceptOutline().getDescriptions().forEach(d -> d.setInitialTerm(null));
	}

	private void generateTemporalParts(ConceptTemplate conceptTemplate) throws IOException, ServiceException {
		final LogicalTemplate logicalTemplate = logicalParserService.parseTemplate(conceptTemplate.getLogicalTemplate());
		conceptTemplate.setFocusConcept(logicalTemplate.getFocusConcepts().isEmpty() ? null : logicalTemplate.getFocusConcepts().get(0));
		updateRelationships(conceptTemplate.getConceptOutline().getRelationships(), logicalTemplate);
		TemplateUtil.validateTermSlots(conceptTemplate, true);
		updateDescriptions(conceptTemplate.getLexicalTemplates(), conceptTemplate.getConceptOutline().getDescriptions(), conceptTemplate.getAdditionalSlots());
	}

	private void updateRelationships(List<Relationship> relationships, LogicalTemplate logicalTemplate) {
		relationships.clear();

		// Add Parents
		for (String focusConcept : logicalTemplate.getFocusConcepts()) {
			final Relationship relationship = new Relationship();
			relationship.setGroupId(0);
			relationship.setType(new ConceptMini(Concepts.ISA));
			relationship.setTarget(new ConceptMini(focusConcept));
			relationships.add(relationship);
		}

		// Add Ungrouped Attributes
		List<Attribute> ungroupedAttributes = logicalTemplate.getUngroupedAttributes();
		ungroupedAttributes.forEach(a -> relationships.add(setRelationshipTypeAndTarget(a, new Relationship())));

		// Add Attribute Groups
		final List<AttributeGroup> attributeGroups = logicalTemplate.getAttributeGroups();
		for (int i = 0; i < attributeGroups.size(); i++) {
			final AttributeGroup attributeGroup = attributeGroups.get(i);
			for (Attribute attribute : attributeGroup.getAttributes()) {
				final Relationship relationship = new Relationship();
				if (attribute.getCardinalityMin() != null) {
					relationship.setCardinalityMin(attribute.getCardinalityMin());
				}
				if (attribute.getCardinalityMax() != null) {
					relationship.setCardinalityMax(attribute.getCardinalityMax());
				}
				relationship.setGroupId(i + 1);
				setRelationshipTypeAndTarget(attribute, relationship);
				relationships.add(relationship);
			}
		}
	}

	private Relationship setRelationshipTypeAndTarget(Attribute attribute, Relationship relationship) {
		relationship.setType(new ConceptMini(attribute.getType()));
		relationship.setTarget(getConceptMiniOrNull(attribute.getValue()));
		if (attribute.getCardinalityMin() != null) {
			relationship.setCardinalityMin(attribute.getCardinalityMin());
		}
		
		if (attribute.getCardinalityMax() != null) {
			relationship.setCardinalityMax(attribute.getCardinalityMax());
		}
		if (attribute.getAllowableRangeECL() != null) {
			relationship.setTargetSlot(new SimpleSlot(attribute.getSlotName(), attribute.getAllowableRangeECL()));
		}
		if (attribute.getSlotReference() != null) {
			relationship.setTargetSlot(new SimpleSlot(attribute.getSlotReference()));
		}
		return relationship;
	}

	private void updateDescriptions(List<LexicalTemplate> lexicalTemplates, List<Description> descriptions, List<String> additionalSlots) {
		Map<String, String> nameToDisplayNameMap = new HashMap<>();
		for (LexicalTemplate lexicalTemplate : lexicalTemplates) {
			nameToDisplayNameMap.put(lexicalTemplate.getName(),
					lexicalTemplate.getDisplayName() != null ? lexicalTemplate.getDisplayName() : lexicalTemplate.getName());
		}

		for (Description description : descriptions) {
			final String termTemplate = description.getTermTemplate();
			String initialTerm = termTemplate;
			final Matcher matcher = TemplateService.TERM_SLOT_PATTERN.matcher(termTemplate);
			while (matcher.find()) {
				final String group = matcher.group(1);
				String replacement = nameToDisplayNameMap.get(group);
				if (replacement == null) {
					if (additionalSlots.contains(group)) {
						replacement = group;
					} else {
						throw new IllegalArgumentException("Term template contains lexical template name which does not exist:" + group);
					}
				}
				initialTerm = initialTerm.replace("$" + group + "$", "[" + replacement + "]");
			}
			description.setInitialTerm(initialTerm);
		}
	}

	private ConceptMini getConceptMiniOrNull(String value) {
		return value == null ? null : new ConceptMini(value);
	}

	public JsonStore getJsonStore() {
		return jsonStore;
	}

	public void clear() {
		templateCache.clear();
	}
}
