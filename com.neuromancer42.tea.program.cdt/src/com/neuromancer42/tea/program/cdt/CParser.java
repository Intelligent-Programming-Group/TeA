package com.neuromancer42.tea.program.cdt;

import com.neuromancer42.tea.core.analyses.ProgramRel;
import com.neuromancer42.tea.core.analyses.ProgramDom;
import com.neuromancer42.tea.core.project.Messages;
import org.eclipse.cdt.core.dom.ast.*;
import org.eclipse.cdt.core.dom.ast.c.ICASTVisitor;
import org.eclipse.cdt.core.dom.ast.gnu.c.GCCLanguage;
import org.eclipse.cdt.core.parser.*;
import org.eclipse.core.runtime.CoreException;

import java.io.File;
import java.util.*;

public class CParser {

    public final ProgramDom<IASTFunctionDeclarator> domM;
    public final ProgramDom<IASTStatement> domP;
    public final ProgramDom<IASTDeclarator> domV;
    public final ProgramDom<IASTExpression> domE;

    public final ProgramRel relMPentry;
    public final ProgramRel relMPexit;
    public final ProgramRel relPPdirect;
    public final ProgramRel relPPtrue;
    public final ProgramRel relPPfalse;
    public final ProgramRel relMV;
    public final ProgramRel relGlobalV;
    private final String fileName;

    public CParser(String fileName) {
        this.fileName = fileName;
        domM = ProgramDom.createDom("M", IASTFunctionDeclarator.class);
        domP = ProgramDom.createDom("P", IASTStatement.class);
        domV = ProgramDom.createDom("V", IASTDeclarator.class);
        domE = ProgramDom.createDom("E", IASTExpression.class);
        relMPentry = ProgramRel.createRel("MPentry", new ProgramDom[]{domM, domP});
        relMPexit = ProgramRel.createRel("MPexit", new ProgramDom[]{domM, domP});
        relPPdirect = ProgramRel.createRel("PPdirect", new ProgramDom[]{domP, domP});
        relPPtrue = ProgramRel.createRel("PPtrue", new ProgramDom[]{domP, domP, domE});
        relPPfalse = ProgramRel.createRel("PPfalse", new ProgramDom[]{domP, domP, domE});
        relMV = ProgramRel.createRel("MV", new ProgramDom[]{domM, domV});
        relGlobalV = ProgramRel.createRel("GlobalV", new ProgramDom[]{domV});
    }


    public void run() {
        File sourceFile = new File(fileName);
        if (!sourceFile.isFile()) {
            Messages.fatal("CParser: the referenced path %s is not a source file", sourceFile.toString());
        }
        FileContent fileContent = FileContent.createForExternalFileLocation(fileName);
        Map<String, String> definedSymbols = new HashMap<>();
        String[] includePaths = new String[0];
        IScannerInfo scannerInfo = new ScannerInfo(definedSymbols, includePaths);
        IParserLogService log = new DefaultLogService();
        IncludeFileContentProvider emptyIncludes = IncludeFileContentProvider.getEmptyFilesProvider();
        int opts = 8; //TODO: read documents?

        IASTTranslationUnit translationUnit = null;
        try {
            translationUnit = GCCLanguage.getDefault().getASTTranslationUnit(fileContent, scannerInfo, emptyIncludes, null, opts, log);
        } catch (CoreException e) {
            Messages.error("CParser: failed to crete parser for file %s, exit.", fileName);
            Messages.fatal(e);
            assert false;
        }

        translationUnit.getDeclarations();
        // TODO: move this into function template
        openDomains();
        translationUnit.accept(new DomainCollector());
        saveDomains();

        openRelations();
        translationUnit.accept(new RelationCollector());
        saveRelations();
    }

    private void openDomains() {
        domM.init();
        domP.init();
        domV.init();
        domE.init();
    }
    private void saveDomains() {
        domM.save();
        domP.save();
        domV.save();
        domE.save();
    }

    private void openRelations() {
        relMPentry.init();
        relMPexit.init();
        relPPdirect.init();
        relPPtrue.init();
        relPPfalse.init();
        relMV.init();
        relGlobalV.init();
    }
    private void saveRelations() {
        relMPentry.save();
        relMPentry.close();
        relMPexit.save();
        relMPexit.close();
        relPPdirect.save();
        relPPdirect.close();
        relPPtrue.save();
        relPPtrue.close();
        relPPfalse.save();
        relPPfalse.close();
        relMV.save();
        relMV.close();
        relGlobalV.save();
        relGlobalV.close();
    }

    private class DomainCollector extends ASTVisitor implements ICASTVisitor{
        public DomainCollector() {
            shouldVisitStatements = true;
            shouldVisitDeclarators = true;
            shouldVisitExpressions = true;
        }

        @Override
        public int visit(IASTDeclarator declarator) {
            if (declarator instanceof IASTFunctionDeclarator) {
                var func = (IASTFunctionDeclarator) declarator;
                Messages.log("CParser: found function <%s>@%s\n%s", func, func.getFileLocation(), func.getRawSignature());
                domM.add(func);
            } else {
                var varName = declarator.getName();
                Messages.log("CParser: found declared var <%s>@%s\n%s", varName, declarator.getFileLocation(), declarator.getRawSignature());
                domV.add(declarator);
            }
            return super.visit(declarator);
        }

        @Override
        public int visit(IASTStatement statement) {
//            Messages.log("CParser: found statement <%s>@%s: \n%s", statement, statement.getFileLocation(), statement.getRawSignature());
            domP.add(statement);
            return super.visit(statement);
        }

        @Override
        public int visit(IASTExpression expression) {
            Messages.log("CParser: found expression %s, %s <%s>:\n%s", expression.getExpressionType(), expression.getValueCategory(), expression, expression.getRawSignature());
            domE.add(expression);
            return super.visit(expression);
        }
    }

    private class RelationCollector extends ASTVisitor implements ICASTVisitor {
        private IASTFunctionDefinition curFunc = null;

        private Set<IASTStatement> openDirectEdges = new HashSet<>();
        private Map<IASTStatement, IASTExpression> openTrueEdges = new HashMap<>();
        private Map<IASTStatement, IASTExpression> openFalseEdges = new HashMap<>();
        private final Map<IASTStatement, Set<IASTStatement>> cachedOpenDirectEdges = new HashMap<>();
        private final Map<IASTStatement, Map<IASTStatement, IASTExpression>> cachedOpenTrueEdges = new HashMap<>();
        private final Map<IASTStatement, Map<IASTStatement, IASTExpression>> cachedOpenFalseEdges = new HashMap<>();

        public RelationCollector() {
            shouldVisitTranslationUnit = true;
            shouldVisitStatements = true;
            shouldVisitDeclarations = true;
            shouldVisitParameterDeclarations = true;
        }

        @Override
        public int visit(IASTTranslationUnit transUnit) {
            IScope scope = transUnit.getScope();
            Messages.log("CParser: creating global variable scope %s (%s)", scope.getScopeName(), scope.getKind());
            return super.visit(transUnit);
        }

        @Override
        public int leave(IASTTranslationUnit transUnit) {
            assert curFunc == null;
            return super.leave(transUnit);
        }

        @Override
        public int visit(IASTDeclaration declaration) {
            if (declaration instanceof IASTFunctionDefinition) {
                if (curFunc != null) {
                    Messages.fatal("CParser: nested function definition unhandled @%s", declaration.getFileLocation());
                }
                IASTFunctionDefinition funcDef = (IASTFunctionDefinition) declaration;
                if (funcDef.getBody() != null) {
                    curFunc = funcDef;
                    var scope = funcDef.getScope();
                    Messages.log("CParser: entering function %s scope %s (%s)", domM.indexOf(curFunc.getDeclarator()), scope, scope.getKind());
                } else {
                    Messages.log("CParser: skip declaration-only function %s", domM.indexOf(((IASTFunctionDefinition) declaration).getDeclarator()));
                    return ASTVisitor.PROCESS_SKIP;
                }
            } else if (declaration instanceof IASTSimpleDeclaration) {
                var simpDecl = (IASTSimpleDeclaration) declaration;
                for (var decl : simpDecl.getDeclarators()) {
                    if (curFunc != null) {
                        Messages.log("CParser: declare local var %s: %s at func %s", domV.indexOf(decl), decl.getRawSignature(), domM.indexOf(curFunc.getDeclarator()));
                        relMV.add(curFunc.getDeclarator(), decl);
                    } else {
                        Messages.log("CParser: global var %s: %s", domV.indexOf(decl), decl.getRawSignature());
                        relGlobalV.add(decl);
                    }
                }
            } else {
                Messages.warn("CParser: unhandled declaration %s", declaration);
            }
            return super.visit(declaration);
        }

        @Override
        public int visit(IASTParameterDeclaration parameterDeclaration) {
            if (curFunc != null) {
                var decl = parameterDeclaration.getDeclarator();
                if (decl == null) {
                    Messages.warn("CParser: anonymous parameter unhandled @%s", parameterDeclaration.getFileLocation());
                } else {
                    Messages.log("CParser: declare parameter %s: %s at func %s", domV.indexOf(decl), decl.getRawSignature(), domM.indexOf(curFunc.getDeclarator()));
                    relMV.add(curFunc.getDeclarator(), decl);
                }
            }
            return super.visit(parameterDeclaration);
        }
        @Override
        public int visit(IASTStatement statement) {
            Messages.log("CParser: entering %s statement %s: \n%s", statement.getClass(), domP.indexOf(statement), statement.getRawSignature());
            if (statement instanceof IASTCompoundStatement) {
                // Compound statements are used to represent the post-state of its block
                // so the program point is added when leaving this statement
                return ASTVisitor.PROCESS_CONTINUE;
            } else if (statement instanceof IASTDeclarationStatement) {
                connectOpenEdges(statement);
                return ASTVisitor.PROCESS_CONTINUE;
            } else if (statement instanceof IASTExpressionStatement) {
                connectOpenEdges(statement);
                return ASTVisitor.PROCESS_CONTINUE;
            } else if (statement instanceof IASTIfStatement) {
                connectOpenEdges(statement);
                // prepare open edge for its then clause
                var cIf = (IASTIfStatement) statement;
                openTrueEdges.put(cIf, cIf.getConditionExpression());
                return ASTVisitor.PROCESS_CONTINUE;
            } else if (statement instanceof IASTWhileStatement) {
                connectOpenEdges(statement);
                // prepare open edge to its loop-body
                var cWhile = (IASTWhileStatement) statement;
                openTrueEdges.put(cWhile, cWhile.getCondition());
                return ASTVisitor.PROCESS_CONTINUE;
            } else if (statement instanceof IASTReturnStatement) {
                return ASTVisitor.PROCESS_SKIP;
            } else {
                Messages.warn("CParser: skip unhandled statement %s: \n%s", statement, statement.getRawSignature());
                return ASTVisitor.PROCESS_SKIP;
            }
        }

        @Override
        public int leave(IASTStatement statement) {
            if (statement instanceof IASTCompoundStatement) {
                // connect block node as the last state of current block
                connectOpenEdges(statement);
                openDirectEdges.add(statement);
            } else if (statement instanceof IASTDeclarationStatement) {
                openDirectEdges.add(statement);
            } else if (statement instanceof IASTExpressionStatement) {
                openDirectEdges.add(statement);
            } else if (statement instanceof IASTIfStatement) {
                // the open edges are the end-edges of else-clause
                // or false edge of if-clause if it has no else-clause
                var ifCls = (IASTIfStatement) statement;
                var thenCls = ifCls.getThenClause();
                mergeCachedOpenEdges(thenCls);
            } else if (statement instanceof IASTWhileStatement) {
                var whileCls = (IASTWhileStatement) statement;
                // the open edges are the end-edges of its body, which should go back to while head
                connectOpenEdges(whileCls);
                // leave the false edge to following edges
                openFalseEdges.put(whileCls, whileCls.getCondition());
            } else {
                Messages.fatal("CParser: unhandled statement type %s", statement.getClass());
            }
            // prepare open edges for peering clauses if it exists
            var parent = statement.getParent();
            if (parent instanceof IASTIfStatement) {
                var pIf = (IASTIfStatement) parent;
                if (statement.equals(pIf.getThenClause()) && pIf.getElseClause() != null) {
                    cachingOpenEdges(statement);
                    openFalseEdges.put(pIf, pIf.getConditionExpression());
                }
            }
            Messages.log("CParser: leaving %s statement %s", statement.getClass(), domP.indexOf(statement));
            return super.leave(statement);
        }

        private void mergeCachedOpenEdges(IASTStatement thenCls) {
            openDirectEdges.addAll(cachedOpenDirectEdges.remove(thenCls));
            openTrueEdges.putAll(cachedOpenTrueEdges.remove(thenCls));
            openFalseEdges.putAll(cachedOpenFalseEdges.remove(thenCls));
        }

        private void cachingOpenEdges(IASTStatement statement) {
            cachedOpenDirectEdges.put(statement, openDirectEdges);
            cachedOpenTrueEdges.put(statement, openTrueEdges);
            cachedOpenFalseEdges.put(statement, openFalseEdges);
            openDirectEdges = new HashSet<>();
            openTrueEdges = new HashMap<>();
            openFalseEdges = new HashMap<>();
        }

        private void clearOpenEdges() {
            openDirectEdges.clear();
            openTrueEdges.clear();
            openFalseEdges.clear();
        }

        private void connectOpenEdges(IASTStatement state) {
            if (openDirectEdges.isEmpty() && openTrueEdges.isEmpty() && openFalseEdges.isEmpty()) {
                Messages.log("CParser: add entry node %s of function %s", domP.indexOf(state), domM.indexOf(curFunc.getDeclarator()));
                relMPentry.add(curFunc.getDeclarator(), state);
            }
            for (var prev : openDirectEdges) {
                Messages.log("CParser: add direct CFG edge (%s,%s)", domP.indexOf(prev), domP.indexOf(state));
                relPPdirect.add(prev, state);
            }
            for (var trueEdge : openTrueEdges.entrySet()) {
                var prev = trueEdge.getKey();
                var cond = trueEdge.getValue();
                Messages.log("CParser: add cond-true CFG edge (%d,%d)-<%s>", domP.indexOf(prev), domP.indexOf(state), cond.getRawSignature());
                relPPtrue.add(prev, state, cond);
            }
            for (var falseEdge : openFalseEdges.entrySet()) {
                var prev = falseEdge.getKey();
                var cond = falseEdge.getValue();
                Messages.log("CParser: add cond-false CFG edge (%d,%d)-!<%s>", domP.indexOf(prev), domP.indexOf(state), cond.getRawSignature());
                relPPfalse.add(prev, state, cond);
            }
            // Note: every open edge should have only one target, so they are useless after connected
            clearOpenEdges();
        }

        @Override
        public int leave(IASTDeclaration declaration) {
            if (declaration instanceof IASTFunctionDefinition) {
                assert curFunc.equals(declaration);
                assert cachedOpenDirectEdges.isEmpty() && cachedOpenTrueEdges.isEmpty() && cachedOpenFalseEdges.isEmpty();
                assert openTrueEdges.isEmpty() && openFalseEdges.isEmpty();
                assert openDirectEdges.size() == 1 && openDirectEdges.contains(curFunc.getBody());
                openDirectEdges.remove(curFunc.getBody());
                relMPexit.add(curFunc.getDeclarator(), curFunc.getBody());
                Messages.log("CParser: leaving function %s, exit node %s", domM.indexOf(curFunc.getDeclarator()), domP.indexOf(curFunc.getBody()));
                curFunc = null;
            }
            return super.leave(declaration);
        }
    }
}
