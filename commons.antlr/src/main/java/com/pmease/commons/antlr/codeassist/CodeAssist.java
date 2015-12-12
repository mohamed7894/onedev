package com.pmease.commons.antlr.codeassist;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.tika.io.IOUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.pmease.commons.antlr.ANTLRv4Lexer;
import com.pmease.commons.antlr.ANTLRv4Parser;
import com.pmease.commons.antlr.ANTLRv4Parser.AlternativeContext;
import com.pmease.commons.antlr.ANTLRv4Parser.AtomContext;
import com.pmease.commons.antlr.ANTLRv4Parser.BlockContext;
import com.pmease.commons.antlr.ANTLRv4Parser.EbnfContext;
import com.pmease.commons.antlr.ANTLRv4Parser.EbnfSuffixContext;
import com.pmease.commons.antlr.ANTLRv4Parser.ElementContext;
import com.pmease.commons.antlr.ANTLRv4Parser.GrammarSpecContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LabeledAltContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LabeledElementContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LabeledLexerElementContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LexerAltContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LexerAtomContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LexerBlockContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LexerElementContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LexerRuleBlockContext;
import com.pmease.commons.antlr.ANTLRv4Parser.LexerRuleSpecContext;
import com.pmease.commons.antlr.ANTLRv4Parser.ModeSpecContext;
import com.pmease.commons.antlr.ANTLRv4Parser.NotSetContext;
import com.pmease.commons.antlr.ANTLRv4Parser.ParserRuleSpecContext;
import com.pmease.commons.antlr.ANTLRv4Parser.RuleBlockContext;
import com.pmease.commons.antlr.ANTLRv4Parser.RuleSpecContext;
import com.pmease.commons.antlr.ANTLRv4Parser.SetElementContext;
import com.pmease.commons.antlr.AntlrUtils;
import com.pmease.commons.antlr.codeassist.ElementSpec.Multiplicity;
import com.pmease.commons.antlr.codeassist.parse.EarleyParser;
import com.pmease.commons.antlr.codeassist.parse.ParentedElement;
import com.pmease.commons.antlr.codeassist.parse.ParseNode;
import com.pmease.commons.antlr.codeassist.parse.ParseState;
import com.pmease.commons.antlr.codeassist.parse.ParsedElement;
import com.pmease.commons.util.StringUtils;

public abstract class CodeAssist implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final Class<? extends Lexer> lexerClass;
	
	private transient Constructor<? extends Lexer> lexerCtor;
	
	private final Map<String, RuleSpec> rules = new HashMap<>();
	
	private final Map<String, Integer> tokenTypesByLiteral = new HashMap<>();

	private final Map<String, Integer> tokenTypesByRule = new HashMap<>();
	
	private final Set<String> blockRuleNames = new HashSet<>();
	
	/**
	 * Code assist constructor
	 * @param lexerClass
	 * 			lexer class to be used to lex code. Other required information such as 
	 * 			grammar files and token file will be derived from the lexer class
	 */
	public CodeAssist(Class<? extends Lexer> lexerClass) {
		this(lexerClass, new String[]{AntlrUtils.getDefaultGrammarFile(lexerClass)}, 
				AntlrUtils.getDefaultTokenFile(lexerClass));
	}

	/**
	 * Code assist constructor.
	 * 
	 * @param lexerClass
	 * 			lexer class to be used to lex code
	 * @param grammarFiles
	 * 			grammar files in class path, relative to class path root
	 * @param tokenFile
	 * 			generated tokens file in class path, relative to class path root
	 */
	public CodeAssist(Class<? extends Lexer> lexerClass, String grammarFiles[], String tokenFile) {
		this.lexerClass = lexerClass;
		tokenTypesByRule.put("EOF", Token.EOF);
		
		try (InputStream is = getClass().getClassLoader().getResourceAsStream(tokenFile)) {
			for (String line: IOUtils.readLines(is)) {
				String key = StringUtils.substringBeforeLast(line, "=");
				Integer value = Integer.valueOf(StringUtils.substringAfterLast(line, "="));
				if (key.startsWith("'"))
					tokenTypesByLiteral.put(key.substring(1, key.length()-1), value);
				else
					tokenTypesByRule.put(key, value);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	
		for (String grammarFile: grammarFiles) {
			try (InputStream is = getClass().getClassLoader().getResourceAsStream(grammarFile)) {
				ANTLRv4Lexer lexer = new ANTLRv4Lexer(new ANTLRInputStream(is));
				CommonTokenStream tokens = new CommonTokenStream(lexer);
				ANTLRv4Parser parser = new ANTLRv4Parser(tokens);
				parser.removeErrorListeners();
				parser.setErrorHandler(new BailErrorStrategy());
				GrammarSpecContext grammarSpecContext = parser.grammarSpec();
				for (RuleSpecContext ruleSpecContext: grammarSpecContext.rules().ruleSpec()) {
					RuleSpec rule = newRule(ruleSpecContext);
					rules.put(rule.getName(), rule);
				}
				for (ModeSpecContext modeSpecContext: grammarSpecContext.modeSpec()) {
					for (LexerRuleSpecContext lexerRuleSpecContext: modeSpecContext.lexerRuleSpec()) {
						RuleSpec rule = newRule(lexerRuleSpecContext);
						rules.put(rule.getName(), rule);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private Constructor<? extends Lexer> getLexerCtor() {
		if (lexerCtor == null) {
			try {
				lexerCtor = lexerClass.getConstructor(CharStream.class);
			} catch (NoSuchMethodException | SecurityException e) {
				throw new RuntimeException(e);
			}
		} 
		return lexerCtor;
	}
	
	private RuleSpec newRule(RuleSpecContext ruleSpecContext) {
		ParserRuleSpecContext parserRuleSpecContext = ruleSpecContext.parserRuleSpec();
		if (parserRuleSpecContext != null) {
			String name = parserRuleSpecContext.RULE_REF().getText();
			List<AlternativeSpec> alternatives = new ArrayList<>();
			RuleBlockContext ruleBlockContext = parserRuleSpecContext.ruleBlock();
			for (LabeledAltContext labeledAltContext: ruleBlockContext.ruleAltList().labeledAlt())
				alternatives.add(newAltenative(labeledAltContext));
			return new RuleSpec(name, alternatives);
		} else {
			return newRule(ruleSpecContext.lexerRuleSpec());
		}
	}
	
	private RuleSpec newRule(LexerRuleSpecContext lexerRuleSpecContext) {
		String name;
		List<AlternativeSpec> alternatives = new ArrayList<>();
		name = lexerRuleSpecContext.TOKEN_REF().getText();
		LexerRuleBlockContext lexerRuleBlockContext = lexerRuleSpecContext.lexerRuleBlock();
		for (LexerAltContext lexerAltContext: lexerRuleBlockContext.lexerAltList().lexerAlt())
			alternatives.add(newAltenative(lexerAltContext));
		return new RuleSpec(name, alternatives);
	}
	
	private AlternativeSpec newAltenative(LexerAltContext lexerAltContext) {
		List<ElementSpec> elements = new ArrayList<>();
		if (lexerAltContext.lexerElements() != null) {
			for (LexerElementContext lexerElementContext: lexerAltContext.lexerElements().lexerElement()) {
				ElementSpec element = newElement(lexerElementContext);
				if (element != null)
					elements.add(element);
			}
		}
		
		return new AlternativeSpec(null, elements);
	}
	
	private AlternativeSpec newAltenative(LabeledAltContext labeledAltContext) {
		String label;
		if (labeledAltContext.id() != null)
			label = labeledAltContext.id().getText();
		else
			label = null;
		
		return newAltenative(label, labeledAltContext.alternative());
	}
	
	@Nullable
	private ElementSpec newElement(LexerElementContext lexerElementContext) {
		LabeledLexerElementContext labeledLexerElementContext = lexerElementContext.labeledLexerElement();
		if (labeledLexerElementContext != null) {
			String label = labeledLexerElementContext.id().getText();
			LexerAtomContext lexerAtomContext = labeledLexerElementContext.lexerAtom();
			if (lexerAtomContext != null)
				return newElement(label, lexerAtomContext, lexerElementContext.ebnfSuffix());
			else 
				return newElement(label, labeledLexerElementContext.block(), lexerElementContext.ebnfSuffix());
		} else if (lexerElementContext.lexerAtom() != null) {
			return newElement(null, lexerElementContext.lexerAtom(), lexerElementContext.ebnfSuffix());
		} else if (lexerElementContext.lexerBlock() != null) {
			return newElement(null, lexerElementContext.lexerBlock(), lexerElementContext.ebnfSuffix());
		} else {
			return null;
		}
	}
	
	private AlternativeSpec newAltenative(@Nullable String label, AlternativeContext alternativeContext) {
		List<ElementSpec> elements = new ArrayList<>();
		for (ElementContext elementContext: alternativeContext.element()) {
			ElementSpec element = newElement(elementContext);
			if (element != null)
				elements.add(element);
		}
		
		return new AlternativeSpec(label, elements);
	}
	
	@Nullable
	private ElementSpec newElement(ElementContext elementContext) {
		LabeledElementContext labeledElementContext = elementContext.labeledElement();
		if (labeledElementContext != null) {
			String label = labeledElementContext.id().getText();
			AtomContext atomContext = labeledElementContext.atom();
			if (atomContext != null)
				return newElement(label, atomContext, elementContext.ebnfSuffix());
			else 
				return newElement(label, labeledElementContext.block(), elementContext.ebnfSuffix());
		} else if (elementContext.atom() != null) {
			return newElement(null, elementContext.atom(), elementContext.ebnfSuffix());
		} else if (elementContext.ebnf() != null) {
			return newElement(elementContext.ebnf());
		} else {
			return null;
		}
	}
	
	@Nullable
	private ElementSpec newElement(String label, AtomContext atomContext, EbnfSuffixContext ebnfSuffixContext) {
		Multiplicity multiplicity = newMultiplicity(ebnfSuffixContext);
		if (atomContext.terminal() != null) {
			if (atomContext.terminal().TOKEN_REF() != null) {
				String ruleName = atomContext.terminal().TOKEN_REF().getText();
				int tokenType = tokenTypesByRule.get(ruleName);
				if (tokenType != Token.EOF)
					return new LexerRuleRefElementSpec(this ,label, multiplicity, tokenType, ruleName);
				else
					return null;
			} else {
				String literal = getLiteral(atomContext.terminal().STRING_LITERAL());
				int tokenType = tokenTypesByLiteral.get(literal);
				return new LiteralElementSpec(label, multiplicity, tokenType, literal);
			}
		} else if (atomContext.ruleref() != null) {
			return new RuleRefElementSpec(this, label, multiplicity, atomContext.ruleref().RULE_REF().getText());
		} else if (atomContext.notSet() != null) {
			return new NotTokenElementSpec(this, label, multiplicity, getNegativeTokenTypes(atomContext.notSet()));
		} else if (atomContext.DOT() != null) {
			return new AnyTokenElementSpec(label, multiplicity);
		} else {
			throw new IllegalStateException();
		}
	}

	private String getLiteral(TerminalNode terminal) {
		String literal = terminal.getText();
		return literal.substring(1, literal.length()-1);
	}
	
	@Nullable
	private ElementSpec newElement(String label, LexerAtomContext lexerAtomContext, EbnfSuffixContext ebnfSuffixContext) {
		Multiplicity multiplicity = newMultiplicity(ebnfSuffixContext);
		if (lexerAtomContext.terminal() != null) {
			if (lexerAtomContext.terminal().TOKEN_REF() != null) {
				String ruleName = lexerAtomContext.terminal().TOKEN_REF().getText();
				Integer tokenType = tokenTypesByRule.get(ruleName);
				if (tokenType == null) // fragment rule
					tokenType = 0;
				if (tokenType != Token.EOF)
					return new LexerRuleRefElementSpec(this, label, multiplicity, tokenType, ruleName);
				else
					return null;
			} else {
				String literal = getLiteral(lexerAtomContext.terminal().STRING_LITERAL());
				Integer tokenType = tokenTypesByLiteral.get(literal);
				if (tokenType == null)
					tokenType = 0;
				return new LiteralElementSpec(label, multiplicity, tokenType, literal);
			}
		} else if (lexerAtomContext.RULE_REF() != null) {
			return new RuleRefElementSpec(this, label, multiplicity, lexerAtomContext.RULE_REF().getText());
		} else if (lexerAtomContext.notSet() != null 
				|| lexerAtomContext.DOT() != null 
				|| lexerAtomContext.LEXER_CHAR_SET()!=null 
				|| lexerAtomContext.range() != null) {
			// Use AnyTokenElementSpec here to as it does not affect our code assist analysis
			return new AnyTokenElementSpec(label, multiplicity);
		} else {
			throw new IllegalStateException();
		}
	}
	
	private Set<Integer> getNegativeTokenTypes(NotSetContext notSetContext) {
		Set<Integer> negativeTokenTypes = new HashSet<>();
		if (notSetContext.setElement() != null) {
			negativeTokenTypes.add(getTokenType(notSetContext.setElement()));
		} else {
			for (SetElementContext setElementContext: notSetContext.blockSet().setElement())
				negativeTokenTypes.add(getTokenType(setElementContext));
		}
		return negativeTokenTypes;
	}
	
	private int getTokenType(SetElementContext setElementContext) {
		Integer tokenType;
		if (setElementContext.STRING_LITERAL() != null) 
			tokenType = tokenTypesByLiteral.get(getLiteral(setElementContext.STRING_LITERAL()));
		else if (setElementContext.TOKEN_REF() != null)
			tokenType = tokenTypesByRule.get(setElementContext.TOKEN_REF().getText());
		else 
			tokenType = null;
		if (tokenType != null)
			return tokenType;
		else
			throw new IllegalStateException();
	}
	
	private Multiplicity newMultiplicity(@Nullable EbnfSuffixContext ebnfSuffixContext) {
		if (ebnfSuffixContext != null) {
			if (ebnfSuffixContext.STAR() != null)
				return Multiplicity.ZERO_OR_MORE;
			else if (ebnfSuffixContext.PLUS() != null)
				return Multiplicity.ONE_OR_MORE;
			else
				return Multiplicity.ZERO_OR_ONE;
		} else {
			return Multiplicity.ONE;
		}
	}
	
	private ElementSpec newElement(@Nullable String label, BlockContext blockContext, @Nullable EbnfSuffixContext ebnfSuffixContext) {
		List<AlternativeSpec> alternatives = new ArrayList<>();
		for (AlternativeContext alternativeContext: blockContext.altList().alternative())
			alternatives.add(newAltenative(null, alternativeContext));
		String ruleName = UUID.randomUUID().toString();
		blockRuleNames.add(ruleName);
		RuleSpec rule = new RuleSpec(ruleName, alternatives);
		rules.put(ruleName, rule);
		return new RuleRefElementSpec(this, label, newMultiplicity(ebnfSuffixContext), ruleName);
	}
	
	private ElementSpec newElement(@Nullable String label, LexerBlockContext lexerBlockContext, @Nullable EbnfSuffixContext ebnfSuffixContext) {
		List<AlternativeSpec> alternatives = new ArrayList<>();
		for (LexerAltContext lexerAltContext: lexerBlockContext.lexerAltList().lexerAlt())
			alternatives.add(newAltenative(lexerAltContext));
		String ruleName = UUID.randomUUID().toString();
		blockRuleNames.add(ruleName);
		RuleSpec rule = new RuleSpec(ruleName, alternatives);
		rules.put(ruleName, rule);
		return new RuleRefElementSpec(this, label, newMultiplicity(ebnfSuffixContext), ruleName);
	}
	
	private ElementSpec newElement(EbnfContext ebnfContext) {
		if (ebnfContext.blockSuffix() != null)
			return newElement(null, ebnfContext.block(), ebnfContext.blockSuffix().ebnfSuffix());
		else
			return newElement(null, ebnfContext.block(), null);
	}
	
	public boolean isBlockRule(String ruleName) {
		return blockRuleNames.contains(ruleName);
	}
	
	@Nullable
	public String getTokenNameByType(int tokenType) {
		for (Map.Entry<String, Integer> entry: tokenTypesByRule.entrySet()) {
			if (entry.getValue() == tokenType)
				return entry.getKey();
		}
		return null;
	}
	
	@Nullable
	public RuleSpec getRule(String ruleName) {
		return rules.get(ruleName);
	}
	
	public final List<Token> lex(String content) {
		try {
			List<Token> tokens = new ArrayList<>();
			Lexer lexer = getLexerCtor().newInstance(new ANTLRInputStream(content));
			lexer.removeErrorListeners();
			Token token = lexer.nextToken();
			while (token.getType() != Token.EOF) {
				if (token.getChannel() == Token.DEFAULT_CHANNEL)
					tokens.add(token);
				token = lexer.nextToken();
			}
			
			return tokens;
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}
	
	public List<InputCompletion> suggest(InputStatus inputStatus, String ruleName) {
		RuleSpec rule = Preconditions.checkNotNull(getRule(ruleName));
		
		String inputContent = inputStatus.getContent();
		List<InputCompletion> inputSuggestions = new ArrayList<>();
		
		/*
		 * Mandatory literals may come after a suggestion (for instance if a method name is suggested, 
		 * the character '(' can be a mandatory literal), so for every suggestion, we need to 
		 * append mandatory literals to make user typing less, however if a suggested text has two or
		 * more different mandatories (this is possible if the suggested text comes from different 
		 * element specs, and each spec calculates a different set of mandatories after the text), we 
		 * should display the suggested text as a single entry in the final suggestion list without 
		 * appending mandatories they are no longer mandatories from aspect of whole rule. Below code
		 * mainly handles this logic.         
		 */
		Map<String, List<ElementCompletion>> grouped = new LinkedHashMap<>();
		for (ElementCompletion completion: suggest(rule, inputStatus)) {
			/*
			 *  key will be the new input (without considering mandatories of course), and we use 
			 *  this to group suggestions to facilitate mandatories calculation and exclusion 
			 *  logic 
			 */
			String key = inputContent.substring(0, completion.getReplaceBegin()) 
					+ completion.getReplaceContent() + inputContent.substring(completion.getReplaceEnd());
			List<ElementCompletion> value = grouped.get(key);
			if (value == null) {
				value = new ArrayList<>();
				grouped.put(key, value);
			}
			value.add(completion);
		}
		
		for (Map.Entry<String, List<ElementCompletion>> entry: grouped.entrySet())	 {
			List<ElementCompletion> value = entry.getValue();
			ElementCompletion completion = value.get(0);
			String description = completion.getDescription();
			String content = entry.getKey(); 
			
			/*
			 * if spec of suggested text matches current input around caret, we 
			 * will replace the match (instead of simply insert the suggested 
			 * text), and in this case, we do not need to append mandatories as
			 * most probably those mandatories already exist in current input
			 */
			if (completion.getReplaceEnd() <= inputStatus.getCaret()) {
				List<String> contents = new ArrayList<>();
				for (ElementCompletion each: value) {
					content = inputContent.substring(0, each.getReplaceBegin()) + each.getReplaceContent();
					ParentedElement parentElement = each.getExpectingElement().getParent();
					ElementSpec elementSpec = each.getExpectingElement().getElement().getSpec();
					for (String mandatory: getMandatoriesAfter(parentElement, elementSpec)) {
						String prevContent = content;
						content += mandatory;

						if (tokenTypesByLiteral.containsKey(mandatory)) {
							/*
							 * if mandatory can be appended without space, the last token 
							 * position should remain unchanged in concatenated content
							 */
							List<Token> tokens = lex(content);
							if (!tokens.isEmpty()) {
								Token lastToken = tokens.get(tokens.size()-1);
								if (lastToken.getStartIndex() != content.length() - mandatory.length()
										|| lastToken.getStopIndex() != content.length()-1) {
									content = prevContent + " " + mandatory;
								}
							} else {
								content = prevContent + " " + mandatory;
							}
						}
					}
					content += inputContent.substring(each.getReplaceEnd());
					
					if (contents.isEmpty()) {
						contents.add(content);
					} else if (!contents.get(contents.size()-1).equals(content)) {
						content = entry.getKey();
						break;
					}
				}
			} 

			/*
			 * Adjust caret to move it to next place expecting user input, that is, we move 
			 * it after all mandatory tokens after the replacement, unless user puts caret 
			 * explicitly in the middle of replacement via suggestion, indicating the 
			 * replacement has some place holders expecting user input. 
			 */
			int caret;
			if (completion.getCaret() == completion.getReplaceContent().length()) {
				// caret is not at the middle of replacement, so we need to move it to 
				// be after mandatory tokens
				caret = content.length() - inputContent.length() + completion.getReplaceEnd(); 
				if (content.equals(entry.getKey())) { 
					// in case mandatory tokens are not added after replacement, 
					// we skip existing mandatory tokens in current input
					String contentAfterCaret = content.substring(caret);
					ParentedElement parentElement = completion.getExpectingElement().getParent();
					ElementSpec elementSpec = completion.getExpectingElement().getElement().getSpec();
					caret += skipMandatories(contentAfterCaret, getMandatoriesAfter(parentElement, elementSpec));
				}
			} else {
				caret = completion.getReplaceBegin() + completion.getReplaceContent().length();
			}
			
			String replaceContent = content.substring(completion.getReplaceBegin(), 
					content.length()-inputContent.length()+completion.getReplaceEnd());
			inputSuggestions.add(new InputCompletion(completion.getReplaceBegin(), 
					completion.getReplaceEnd(), replaceContent, caret, description));
		}
		
		/*
		 * remove duplicate suggestions and suggestions the same as current input
		 */
		Set<String> suggestedContents = Sets.newHashSet(inputStatus.getContent());
		for (Iterator<InputCompletion> it = inputSuggestions.iterator(); it.hasNext();) {
			String content = it.next().complete(inputStatus).getContent();
			if (suggestedContents.contains(content))
				it.remove();
			else
				suggestedContents.add(content);
		}
		return inputSuggestions;
	}
	
	private ParentedElement getElementExpectingTerminal(ParentedElement parent, ParsedElement element) {
		parent = new ParentedElement(parent, element);
		ParseNode node = element.getNode();
		if (node != null) { 
			element = node.getParsedElements().get(node.getParsedElements().size()-1);
			return getElementExpectingTerminal(parent, element);
		} else {
			return parent;
		}
	}
	
	private void fillSuggestions(List<ElementSuggestion> suggestions, EarleyParser parser, 
			ParseState state, InputStatus inputStatus) {
		for (ParseNode node: state.getNodesExpectingTerminal()) {
			List<ParsedElement> rootElements = parser.assumeCompleted(node, state.getNextTokenIndex());
			String matchWith;
			if (state.getNextTokenIndex() != 0) {
				int stopIndex = parser.getTokens().get(state.getNextTokenIndex()-1).getStopIndex()+1;
				matchWith = inputStatus.getContent().substring(stopIndex, inputStatus.getCaret());
			} else {
				matchWith = inputStatus.getContentBeforeCaret();
			}
			matchWith = StringUtils.trimStart(matchWith);
			for (ParsedElement rootElement: rootElements) {
				List<ParentedElement> expectingElements = new ArrayList<>();
				ParentedElement expectingElement = getElementExpectingTerminal(null, rootElement);
				expectingElements.add(expectingElement);
				expectingElement = expectingElement.getParent();
				while (expectingElement != null) {
					if (expectingElement.getElement().getNode().getParsedElements().size() == 1) {
						expectingElements.add(expectingElement);
						expectingElement = expectingElement.getParent();
					} else {
						break;
					}
				}
				
				Collections.reverse(expectingElements);

				for (ParentedElement element: expectingElements) {
					List<InputSuggestion> inputSuggestions = suggest(element, matchWith);
					if (inputSuggestions != null) {
						suggestions.add(new ElementSuggestion(element, matchWith, inputSuggestions));
						break;
					}
				}
			}
		}
 	}
	
	private List<ElementCompletion> suggest(RuleSpec spec, InputStatus inputStatus) {
		List<ElementCompletion> completions = new ArrayList<>();
		
		List<ElementSuggestion> suggestions = new ArrayList<>();
		
		List<Token> tokens = lex(inputStatus.getContentBeforeCaret());
		EarleyParser parser = new EarleyParser(spec, tokens);
		
		if (parser.getStates().size() >= 1) {
			ParseState lastState = parser.getStates().get(parser.getStates().size()-1);
			fillSuggestions(suggestions, parser, lastState, inputStatus);
		}

		/*
		 * do another match by not considering the last token. This is necessary for 
		 * instance for below cases:
		 * 1. when the last token matches either a keyword or part of an identifier. 
		 * For instance, the last token can be keyword 'for', but can also match 
		 * identifier 'forme' if the spec allows.  
		 * 2. assume we have a query rule containing multiple criterias, with each 
		 * criteria composed of key/value pair key:value. value needs to be quoted 
		 * if it contains spaces. In this case, we want to achieve the effect that 
		 * if user input value containing spaces without surrounding quotes, we 
		 * suggest the user to quote the value. 
		 */
		if (parser.getStates().size() >= 2) {
			ParseState stateBeforeLast = parser.getStates().get(parser.getStates().size()-2);
			fillSuggestions(suggestions, parser, stateBeforeLast, inputStatus);
		}

		String inputContent = inputStatus.getContent();
		for (ElementSuggestion suggestion: suggestions) {
			int replaceStart = inputStatus.getCaret() - suggestion.getMatchWith().length();
			int replaceEnd = inputStatus.getCaret();
			String contentAfterReplaceStart = inputContent.substring(replaceStart);
			tokens = lex(contentAfterReplaceStart);
			
			/*
			 * if input around the caret matches spec of the suggestion, we then replace 
			 * the matched text with suggestion, instead of simply inserting the 
			 * suggested content
			 */
			if (!tokens.isEmpty() && tokens.get(0).getStartIndex() == 0) {
				ElementSpec elementSpec = (ElementSpec) suggestion.getExpectingElement().getElement().getSpec();
				int matchDistance = elementSpec.getMatchDistance(tokens);
				if (matchDistance > 0) {
					int charIndex = tokens.get(matchDistance-1).getStopIndex()+1;
					if (replaceStart + charIndex > replaceEnd)
						replaceEnd = replaceStart + charIndex;
				}
			}

			String before = inputContent.substring(0, replaceStart);

			for (InputSuggestion inputSuggestion: suggestion.getInputSuggestions()) {
				int nextTokenIndex = suggestion.getExpectingElement().getElement().getNextTokenIndex();
				if (nextTokenIndex != 0) { 
					Token lastToken = parser.getTokens().get(nextTokenIndex-1);
					tokens = lex(before + inputSuggestion.getContent());
					/*
					 * ignore the suggestion if we can not append the suggested content directly. 
					 * This normally indicates that a space is required before the suggestion, 
					 * and we will show the suggestion when user presses the space to make the 
					 * suggestion list less confusing
					 */
					if (tokens.size() > nextTokenIndex) {
						Token newToken = tokens.get(nextTokenIndex-1);
						if (lastToken.getStartIndex() != newToken.getStartIndex()
								|| lastToken.getStopIndex() != newToken.getStopIndex()) {
							continue;
						}
					} else {
						continue;
					}
				} 
				
				completions.add(new ElementCompletion(suggestion.getExpectingElement(), replaceStart, 
						replaceEnd, inputSuggestion.getContent(), inputSuggestion.getCaret(), 
						inputSuggestion.getDescription()));
			}
		}
		return completions;
	}
	
	private int skipMandatories(String content, List<String> mandatories) {
		String mandatory = StringUtils.join(mandatories, "");
		mandatory = StringUtils.deleteWhitespace(mandatory);
		if (mandatory.length() != 0 && content.length() != 0) {
			int mandatoryIndex = 0;
			int contentIndex = 0;
			while (true) {
				char contentChar = content.charAt(contentIndex);
				/*
				 * space may exist between mandatories in user input, but should 
				 * not exist in ANTLR grammar  
				 */
				if (!Character.isWhitespace(contentChar)) {
					if (contentChar != mandatory.charAt(mandatoryIndex))
						break;
					mandatoryIndex++;
				} 
				contentIndex++;
				if (mandatoryIndex == mandatory.length() || contentIndex == content.length())
					break;
			}
			if (mandatoryIndex == mandatory.length())
				return contentIndex;
			else
				return 0;
		} else {
			return 0;
		}
	}

	/*
	 * Get mandatory literals after specified node. For instance a method may have 
	 * below rule:
	 * methodName '(' argList ')'
	 * The mandatories after node methodName will be '('. When a method name is 
	 * suggested, we should add '(' and moves caret after '(' to avoid unnecessary
	 * key strokes
	 */
	private List<String> getMandatoriesAfter(ParentedElement parentElement, ElementSpec elementSpec) {
		List<String> literals = new ArrayList<>();
		if (parentElement != null && elementSpec != null 
				&& (elementSpec.getMultiplicity() == Multiplicity.ONE || elementSpec.getMultiplicity() == Multiplicity.ZERO_OR_ONE)) {
			AlternativeSpec alternativeSpec = parentElement.getElement().getNode().getAlternativeSpec();
			int specIndex = alternativeSpec.getElements().indexOf(elementSpec);
			if (specIndex == alternativeSpec.getElements().size()-1) {
				elementSpec = parentElement.getElement().getSpec();
				parentElement = parentElement.getParent();
				return getMandatoriesAfter(parentElement, elementSpec);
			} else {
				elementSpec = alternativeSpec.getElements().get(specIndex+1);
				if (elementSpec.getMultiplicity() == Multiplicity.ONE
						|| elementSpec.getMultiplicity() == Multiplicity.ONE_OR_MORE) {
					MandatoryScan scan = elementSpec.scanMandatories(new HashSet<String>());
					literals = scan.getMandatories();
					if (!scan.isStop())
						literals.addAll(getMandatoriesAfter(parentElement, elementSpec));
				}
			}
		} 
		return literals;
	}

	protected abstract List<InputSuggestion> suggest(ParentedElement element, String matchWith);

}
