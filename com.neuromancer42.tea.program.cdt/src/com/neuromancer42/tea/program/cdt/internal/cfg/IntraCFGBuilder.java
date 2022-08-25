package com.neuromancer42.tea.program.cdt.internal.cfg;

import com.neuromancer42.tea.core.project.Messages;
import com.neuromancer42.tea.core.util.IndexMap;
import org.eclipse.cdt.codan.core.model.cfg.*;
import org.eclipse.cdt.codan.internal.core.cfg.*;
import org.eclipse.cdt.core.dom.ast.*;

import java.util.ArrayList;
import java.util.List;

// general purpose
// translate the complex source code into a control flow graph
// each edge in the graph represents a simple computation of register values
// 1. arithmetical computations
// 2. read variables / read from memory locations
// 3. write variables / store to memory locations
// 4. computation with controls: logical and/or, ternary expression
// 5. function calls
// 6. returns
// 7. control statements: if, while, do-while, for, switch
// 8. declarators

// generally, this control flow is just a determinated variety of C specification
// in that: some evaluation orders are unspecified
// e.g. operands of arithmetical computations, arguments of function calls
public class IntraCFGBuilder {
    private final IASTTranslationUnit transUnit;
    private IStartNode start;
    private List<IExitNode> exits;
    private IConnectorNode outerContinueTarget;
    private boolean outerContinueBackward;
    private IConnectorNode outerBreakTarget;

    public IntraCFGBuilder(IASTTranslationUnit tu) {
        this.transUnit = tu;
        registers = new IndexMap<>();
    }

    private final IndexMap<IASTExpression> registers; // sub-expressions should compute from registers and load into registers
    private List<IBasicBlock> unreachable;

    public IntraCFG build(IASTFunctionDefinition fDef) {
        IASTStatement fBody = fDef.getBody();
        start = new FuncEntryNode(fDef);
        exits = new ArrayList<>();
        outerContinueTarget = null;
        outerContinueBackward = true;
        outerBreakTarget = null;
        unreachable = new ArrayList<>();
        expandGraph(start, fBody);
        IntraCFG cfg = new IntraCFG(start, exits);
        cfg.setUnconnectedNodes(unreachable);
        return cfg;
    }

    private IBasicBlock expandGraph(IBasicBlock prevNode, IASTStatement statement) {
        if (statement == null || statement instanceof IASTNullStatement) {
            return prevNode;
        } else if (statement instanceof IASTCompoundStatement) {
            IASTStatement[] subStatements = ((IASTCompoundStatement) statement).getStatements();
            for (IASTStatement subStatement : subStatements) {
                prevNode = expandGraph(prevNode, subStatement);
            }
            return prevNode;
        } else if (statement instanceof IASTExpressionStatement) {
            IASTExpression expr = unparenthesize(((IASTExpressionStatement) statement).getExpression());
            return handleExpression(prevNode, expr);
        } else if (statement instanceof IASTDeclarationStatement) {
            IASTDeclaration decl = ((IASTDeclarationStatement) statement).getDeclaration();
            if (decl instanceof IASTSimpleDeclaration) {
                IASTDeclarator[] dtors = ((IASTSimpleDeclaration) decl).getDeclarators();
                for (IASTDeclarator dtor: dtors) {
                    IASTInitializer initializer = dtor.getInitializer();
                    if (initializer == null)
                        continue;
                    if (initializer instanceof IASTEqualsInitializer) {
                        IASTInitializerClause initClause = ((IASTEqualsInitializer) initializer).getInitializerClause();
                        IASTExpression initExpr = unparenthesize((IASTExpression) initClause);
                        prevNode = handleRvalue(prevNode, initExpr);
                        int reg = fetchRegister(initExpr);
                        // TODO: change store target to stack variable
                        IBasicBlock storeNode = new StoreNode(null, reg);
                        connect(prevNode, storeNode);
                        prevNode = storeNode;
                    } else {
                        Messages.error("CParser: skip unhandled declaration initializer %s[%s]", initializer.getClass().getSimpleName(), initializer.getRawSignature());
                    }
                }
                return prevNode;
            } else if (decl instanceof IASTFunctionDefinition) {
                Messages.fatal("CParser: do not support nested function definition");
            }
            Messages.error("CParser: skip unhandled declaration %s[%s]", decl.getClass().getSimpleName(), decl.getRawSignature());
        } else if (statement instanceof IASTIfStatement) {
            IASTIfStatement cIf = (IASTIfStatement) statement;
            prevNode = handleIfStatement(prevNode, cIf);

            return prevNode;
        } else if (statement instanceof IASTWhileStatement) {
            IASTWhileStatement cWhile = (IASTWhileStatement) statement;
            prevNode = handleWhileStatement(prevNode, cWhile);

            return prevNode;
        } else if (statement instanceof IASTDoStatement) {
            IASTDoStatement cDo = (IASTDoStatement) statement;
            prevNode = handleDoWhileStatement(prevNode, cDo);

            return prevNode;
        } else if (statement instanceof IASTForStatement) {
            IASTForStatement cFor = (IASTForStatement) statement;
            prevNode = handleForStatement(prevNode, cFor);

            return prevNode;
        } else if (statement instanceof IASTBreakStatement) {
            if (outerBreakTarget != null) {
                jump(prevNode, outerBreakTarget, false);
                return null;
            } else {
                Messages.warn("CParser: skip invalid %s in no loop or switch", statement.getClass().getSimpleName());
                return prevNode;
            }
        } else if (statement instanceof IASTContinueStatement) {
            if (outerContinueTarget != null) {
                jump(prevNode, outerContinueTarget, outerContinueBackward);
                return null;
            } else {
                Messages.warn("CParser: skip invalid %s in no loop", statement.getClass().getSimpleName());
                return prevNode;
            }
        } else if (statement instanceof IASTReturnStatement) {
            IASTReturnStatement ret = (IASTReturnStatement) statement;
            IASTExpression retExpr = ret.getReturnValue();
            if (retExpr != null) {
                retExpr = unparenthesize(retExpr);
                prevNode = handleRvalue(prevNode, retExpr);
            }
            ReturnNode retNode = new ReturnNode();
            connect(prevNode, retNode);
            retNode.setStartNode(start);
            exits.add(retNode);

            return null;
        }

        Messages.error("CParser: skip unsupported C statement %s[%s]", statement.getClass().getSimpleName(), statement.getRawSignature());
        return prevNode;
    }

    private IBasicBlock handleExpression(IBasicBlock prevNode, IASTExpression expression) {
        if (expression instanceof IASTBinaryExpression) {
            IASTBinaryExpression binExpr = (IASTBinaryExpression) expression;
            int op = binExpr.getOperator();
            if (op == IASTBinaryExpression.op_assign) {
                IASTExpression lhs = unparenthesize(binExpr.getOperand1());
                prevNode = handleLvalue(prevNode, lhs);

                IASTExpression rhs = unparenthesize(binExpr.getOperand2());
                prevNode = handleRvalue(prevNode, rhs);

                Messages.log("CParser: add assignment %s[%s] <- %s[%s]", lhs.getClass().getSimpleName(), lhs.getRawSignature(), rhs.getClass().getSimpleName(), rhs.getRawSignature());
                int rReg = fetchRegister(rhs);
                IBasicBlock storeNode = new StoreNode(lhs, rReg);
                connect(prevNode, storeNode);

                return storeNode;
            } else if (binExpr.isLValue()) { // equivalent to op is all compound assignment operators (except op_assign)
                IASTExpression lval = unparenthesize(binExpr.getOperand1());
                prevNode = handleLvalue(prevNode, lval);

                int prevReg = createRegister(lval);
                IBasicBlock loadNode = new LoadNode(lval, prevReg);
                connect(prevNode, loadNode);
                prevNode = loadNode;

                IASTExpression rval = unparenthesize(binExpr.getOperand2());
                prevNode = handleRvalue(prevNode, rval);

                int postReg = createRegister(expression);
                IBasicBlock evalNode = new EvalNode(expression, postReg);
                connect(prevNode, evalNode);
                prevNode = loadNode;

                IBasicBlock storeNode = new StoreNode(lval, postReg);
                connect(prevNode, storeNode);
                prevNode = storeNode;

                return prevNode;
            } else if (op == IASTBinaryExpression.op_logicalAnd || op == IASTBinaryExpression.op_logicalOr){
                prevNode = handleShortcircuitExpression(prevNode, binExpr);
                return prevNode;
            } else {
                Messages.warn("CParser: expression result un-used for [%s]", expression.getRawSignature());
                prevNode = handleRvalue(prevNode, expression);
                return prevNode;
            }
        } else if (expression instanceof IASTUnaryExpression) {
            IASTUnaryExpression unaryExpr = (IASTUnaryExpression) expression;
            int op = unaryExpr.getOperator();
            if (op == IASTUnaryExpression.op_prefixIncr || op == IASTUnaryExpression.op_prefixDecr
                    || op == IASTUnaryExpression.op_postFixIncr || op == IASTUnaryExpression.op_postFixDecr
            ) {
                IASTExpression obj = unparenthesize(unaryExpr.getOperand());
                prevNode = handleLvalue(prevNode, obj);

                int prevReg = createRegister(obj);
                IBasicBlock loadNode = new LoadNode(obj, prevReg);
                connect(prevNode, loadNode);
                prevNode = loadNode;

                int postReg = createRegister(expression);
                IBasicBlock evalNode = new EvalNode(expression, postReg);
                connect(prevNode, evalNode);
                prevNode = evalNode;

                IBasicBlock storeNode = new StoreNode(obj, postReg);
                connect(prevNode,storeNode);
                prevNode = storeNode;

                return prevNode;
            } else {
                Messages.warn("CParser: expression result un-used for [%s]", expression.getRawSignature());
                prevNode = handleRvalue(prevNode, expression);

                return prevNode;
            }
        } else if (expression instanceof IASTFunctionCallExpression) {
            prevNode = handleRvalue(prevNode, expression);
            return prevNode;
        }
        Messages.error("CParser: skip unsupported %s [%s]", expression.getClass().getSimpleName(), expression.getRawSignature());
        return prevNode;
    }

    // Note: this method does not check if the lvalue is modifiable
    private IBasicBlock handleLvalue(IBasicBlock prevNode, IASTExpression expression) {
        if (expression instanceof IASTIdExpression) {
            // No need to compute target
            return prevNode;
        } else if (expression instanceof IASTUnaryExpression) {
            IASTUnaryExpression unaryExpr = (IASTUnaryExpression) expression;
            int op = unaryExpr.getOperator();
            IASTExpression inner = unparenthesize(unaryExpr.getOperand());
            if (op == IASTUnaryExpression.op_star) {
                prevNode = handleRvalue(prevNode, inner);
                return prevNode;
            } else if (op == IASTUnaryExpression.op_bracketedPrimary)
                Messages.fatal("CParser: brackets should have been unparenthesized for [%s]", expression.getRawSignature());
            Messages.fatal("CParser: modifying non-lvalue %s [%s]", expression.getClass().getSimpleName(), expression.getRawSignature());
        } else if (expression instanceof IASTBinaryExpression) {
            IASTBinaryExpression binaryExpr = (IASTBinaryExpression) expression;
            int op = binaryExpr.getOperator();
            if (op == IASTBinaryExpression.op_pmdot) {
                IASTExpression base = unparenthesize(binaryExpr.getOperand1());
                prevNode = handleLvalue(prevNode, base);
                return prevNode;
            } else if (op == IASTBinaryExpression.op_pmarrow) {
                IASTExpression ptr = unparenthesize(binaryExpr.getOperand1());
                prevNode = handleRvalue(prevNode, ptr);
                return prevNode;
            }
            Messages.fatal("CParser: modifying non-lvalue %s [%s]", expression.getClass().getSimpleName(), expression.getRawSignature());
        } else if (expression instanceof IASTArraySubscriptExpression) {
            IASTArraySubscriptExpression arraySubExpr = (IASTArraySubscriptExpression) expression;

            IASTExpression array = unparenthesize(arraySubExpr.getArrayExpression());
            prevNode = handleRvalue(prevNode, array);

            IASTExpression subscript = unparenthesize((IASTExpression) arraySubExpr.getArgument());
            prevNode = handleRvalue(prevNode, subscript);

            return prevNode;
        } else if (expression instanceof IASTLiteralExpression) {
            IASTLiteralExpression literal = (IASTLiteralExpression) expression;
            if (literal.getKind() == IASTLiteralExpression.lk_string_literal) {
                return prevNode;
            }
        }
        Messages.fatal("CParser: non-lvalue %s [%s]", expression.getClass().getSimpleName(), expression.getRawSignature());
        return null;
    }

    private IBasicBlock handleRvalue(IBasicBlock prevNode, IASTExpression expression) {
        if (expression instanceof IASTLiteralExpression) {
            registers.add(expression);
            // do nothing, no load/store or evaluation
            return prevNode;
        } else if (expression instanceof IASTIdExpression) {
            int reg = createRegister(expression);
            IBasicBlock loadNode = new LoadNode(expression, reg);
            connect(prevNode, loadNode);
            prevNode = loadNode;

            return prevNode;
        } else if (expression instanceof IASTUnaryExpression) {
            IASTUnaryExpression unaryExpr = (IASTUnaryExpression) expression;
            int op = unaryExpr.getOperator();
            IASTExpression inner = unparenthesize(unaryExpr.getOperand());
            if (op == IASTUnaryExpression.op_bracketedPrimary)
                Messages.fatal("CParser: brackets should have been unparenthesized for [%s]", expression.getRawSignature());
            else if (op == IASTUnaryExpression.op_star) {
                prevNode = handleRvalue(prevNode, inner);

                int reg = createRegister(expression);
                IBasicBlock loadNode = new LoadNode(expression, reg);
                connect(prevNode, loadNode);
                prevNode = loadNode;

                return prevNode;
            } else if (op == IASTUnaryExpression.op_plus || op == IASTUnaryExpression.op_minus || op == IASTUnaryExpression.op_not) {
                prevNode = handleRvalue(prevNode, inner);

                int reg = createRegister(expression);
                IBasicBlock evalNode = new EvalNode(expression, reg);
                connect(prevNode, evalNode);
                prevNode = evalNode;

                return prevNode;
            } else if (op == IASTUnaryExpression.op_prefixIncr || op == IASTUnaryExpression.op_prefixDecr
                    || op == IASTUnaryExpression.op_postFixIncr || op == IASTUnaryExpression.op_postFixDecr) {
                Messages.warn("CParser: side effect in right value %s[%s]", expression.getClass().getSimpleName(), expression.getRawSignature());

                prevNode = handleExpression(prevNode, expression);

                return prevNode;
            } else if (op == IASTUnaryExpression.op_amper) {
                prevNode = handleLvalue(prevNode, inner);

                int reg = createRegister(expression);
                IBasicBlock evalNode = new EvalNode(expression, reg);
                connect(prevNode, evalNode);
                prevNode = evalNode;

                return prevNode;
            }
        } else if (expression instanceof IASTBinaryExpression) {
            IASTBinaryExpression binExpr = (IASTBinaryExpression) expression;
            int op = binExpr.getOperator();
            if (binExpr.isLValue()) {
                Messages.warn("CParser: side effect in right value %s[%s]", expression.getClass().getSimpleName(), expression.getRawSignature());
                prevNode = handleExpression(prevNode, expression);

                return prevNode;
            } else if (op == IASTBinaryExpression.op_pmdot) {
                IASTExpression base = unparenthesize(binExpr.getOperand1());
                prevNode = handleLvalue(prevNode, base);

                int reg = createRegister(expression);
                IBasicBlock loadNode = new LoadNode(expression, reg);
                connect(prevNode, loadNode);
                prevNode = loadNode;

                return prevNode;
            } else if (op == IASTBinaryExpression.op_pmarrow) {
                IASTExpression ptr = unparenthesize(binExpr.getOperand1());
                prevNode = handleRvalue(prevNode, ptr);

                int reg = createRegister(expression);
                IBasicBlock loadNode = new LoadNode(expression, reg);
                connect(prevNode, loadNode);
                prevNode = loadNode;

                return prevNode;
            } else if (op == IASTBinaryExpression.op_logicalAnd || op == IASTBinaryExpression.op_logicalOr) {
                prevNode = handleShortcircuitExpression(prevNode, binExpr);

                int reg = createRegister(expression);
                IBasicBlock evalNode = new EvalNode(expression, reg);
                connect(prevNode, evalNode);
                prevNode = evalNode;

                return prevNode;
            } else {
                // TODO: C standard does not specify the evaluation order of arithmetical expressions
                IASTExpression op1 = unparenthesize(binExpr.getOperand1());
                prevNode = handleRvalue(prevNode, op1);

                IASTExpression op2 = unparenthesize(binExpr.getOperand2());
                prevNode = handleRvalue(prevNode, op2);

                int reg = createRegister(expression);
                IBasicBlock evalNode = new EvalNode(expression, reg);
                connect(prevNode, evalNode);
                prevNode = evalNode;

                return prevNode;
            }
        } else if (expression instanceof IASTArraySubscriptExpression) {
            IASTArraySubscriptExpression arraySubExpr = (IASTArraySubscriptExpression) expression;

            IASTExpression array = unparenthesize(arraySubExpr.getArrayExpression());
            prevNode = handleRvalue(prevNode, array);

            IASTExpression subscript = unparenthesize((IASTExpression) arraySubExpr.getArgument());
            prevNode = handleRvalue(prevNode, subscript);

            int reg = createRegister(expression);
            IBasicBlock loadNode = new LoadNode(expression, reg);
            connect(prevNode, loadNode);
            prevNode = loadNode;

            return prevNode;
        } else if (expression instanceof IASTFunctionCallExpression) {
            IASTFunctionCallExpression invk = (IASTFunctionCallExpression) expression;

            IASTExpression fNameExpr = unparenthesize(invk.getFunctionNameExpression());
            prevNode = handleRvalue(prevNode, fNameExpr);

            // TODO: C standard does not specify the evaluation order of arguments
            for (IASTInitializerClause fArg : invk.getArguments()) {
                IASTExpression fArgExpr = unparenthesize((IASTExpression) fArg);
                if (fArgExpr instanceof IASTFunctionCallExpression) {
                    Messages.warn("CParser: embedded function call in [%s]", expression.getRawSignature());
                }
                prevNode = handleRvalue(prevNode, fArgExpr);
            }

            int reg = createRegister(expression);
            IBasicBlock evalNode = new EvalNode(expression, reg);
            connect(prevNode, evalNode);
            prevNode = evalNode;

            return prevNode;
        }
        registers.add(expression);
        Messages.error("CParser: skip unsupported C Rvalue expression %s[%s]", expression.getClass().getSimpleName(), expression.getRawSignature());
        return prevNode;
    }

    private IBasicBlock handleShortcircuitExpression(IBasicBlock prevNode, IASTBinaryExpression expression) {
        int op = expression.getOperator();
        assert op == IASTBinaryExpression.op_logicalAnd || op == IASTBinaryExpression.op_logicalOr;

        IASTExpression lval = unparenthesize(expression.getOperand1());
        prevNode = handleRvalue(prevNode, lval);

        int lReg = fetchRegister(lval);
        CondNode shortCircuit = new CondNode(lval, lReg);
        connect(prevNode, shortCircuit);

        IBasicBlock trueNode = new LabelNode(IBranchNode.THEN);
        IBasicBlock falseNode = new LabelNode(IBranchNode.ELSE);
        connect(shortCircuit, trueNode);
        connect(shortCircuit, falseNode);

        IASTExpression rval = unparenthesize(expression.getOperand2());
        if (op == IASTBinaryExpression.op_logicalAnd) {
            trueNode = handleRvalue(trueNode, rval);
        } else {
            falseNode = handleRvalue(falseNode, rval);
        }

        IConnectorNode phiNode = new PhiNode();
        shortCircuit.setMergeNode(phiNode);
        jump(trueNode, phiNode, false);
        jump(falseNode, phiNode, false);
        fixPhi(phiNode);

        prevNode = phiNode;
        return prevNode;
    }

    private IBasicBlock handleIfStatement(IBasicBlock prevNode, IASTIfStatement cIf) {
        IASTExpression condExpr = unparenthesize(cIf.getConditionExpression());
        prevNode = handleRvalue(prevNode, condExpr);

        int condReg = fetchRegister(condExpr);
        CondNode ifNode = new CondNode(condExpr, condReg);
        connect(prevNode, ifNode);

        IBasicBlock thenNode = new LabelNode(IBranchNode.THEN);
        connect(ifNode, thenNode);
        thenNode = expandGraph(thenNode, cIf.getThenClause());

        IBasicBlock elseNode = new LabelNode(IBranchNode.ELSE);
        connect(ifNode, elseNode);
        elseNode = expandGraph(elseNode, cIf.getElseClause());

        IConnectorNode phiNode = new PhiNode();
        ifNode.setMergeNode(phiNode);
        jump(thenNode, phiNode, false);
        jump(elseNode, phiNode, false);
        fixPhi(phiNode);

        prevNode = phiNode;
        return prevNode;
    }

    private IBasicBlock handleWhileStatement(IBasicBlock prevNode, IASTWhileStatement cWhile) {
        IConnectorNode continueNode = new PhiNode();
        IConnectorNode breakNode = new PhiNode();

        connect(prevNode, continueNode);

        IASTExpression condExpr = cWhile.getCondition();
        IASTStatement loopBody = cWhile.getBody();

//        if (condExpr == null) {
//            IBasicBlock bodyEnd = handleLoopBody(continueNode, loopBody, continueNode, breakNode);
//            jump(bodyEnd, continueNode, true);

        condExpr = unparenthesize(condExpr);
        IBasicBlock evalCondNode = handleRvalue(continueNode, condExpr);

        int condReg = fetchRegister(condExpr);
        CondNode whileNode = new CondNode(condExpr, condReg);
        connect(evalCondNode, whileNode);
        whileNode.setMergeNode(breakNode);

        IBasicBlock bodyStart = new LabelNode(IBranchNode.THEN);
        connect(evalCondNode, bodyStart);
        IBasicBlock bodyEnd = handleLoopBody(bodyStart, loopBody, continueNode, true, breakNode);
        jump(bodyEnd, continueNode, true);

        IBasicBlock loopEnd = new LabelNode(IBranchNode.ELSE);
        connect(evalCondNode, loopEnd);
        jump(loopEnd, breakNode, false);

        prevNode = breakNode;
        return prevNode;
    }

    private IBasicBlock handleDoWhileStatement(IBasicBlock prevNode, IASTDoStatement cDoWhile) {
        IConnectorNode continueNode = new PhiNode();
        IConnectorNode breakNode = new PhiNode();

        IASTExpression condExpr = cDoWhile.getCondition();
        IASTStatement loopBody = cDoWhile.getBody();

        IConnectorNode bodyStart = new PhiNode();
        connect(prevNode, bodyStart);

        IBasicBlock bodyEnd = handleLoopBody(bodyStart, loopBody, continueNode, false, breakNode);
        jump(bodyEnd, continueNode, false);

        IBasicBlock evalCondNode = handleRvalue(continueNode, condExpr);
        int condReg = fetchRegister(condExpr);
        CondNode doWhileNode = new CondNode(condExpr, condReg);
        connect(evalCondNode, doWhileNode);
        doWhileNode.setMergeNode(breakNode);

        IBranchNode trueNode = new LabelNode(IBranchNode.THEN);
        connect(doWhileNode, trueNode);
        jump(trueNode, bodyStart, true);

        IBranchNode loopEnd = new LabelNode(IBranchNode.ELSE);
        connect(doWhileNode, loopEnd);
        jump(loopEnd, breakNode, false);

        prevNode = breakNode;
        return prevNode;
    }


    private IBasicBlock handleForStatement(IBasicBlock prevNode, IASTForStatement cFor) {
        IConnectorNode continueNode = new PhiNode();
        IConnectorNode breakNode = new PhiNode();

        IASTStatement init = cFor.getInitializerStatement();
        prevNode = expandGraph(prevNode, init);
        IConnectorNode condStart = new PhiNode();
        connect(prevNode, condStart);

        IASTExpression condExpr = cFor.getConditionExpression();
        IBasicBlock evalCondNode = handleRvalue(condStart, condExpr);
        int condReg = fetchRegister(condExpr);
        CondNode forNode = new CondNode(condExpr, condReg);
        connect(evalCondNode, forNode);
        forNode.setMergeNode(breakNode);

        IASTStatement loopBody = cFor.getBody();
        IBranchNode bodyStart = new LabelNode(IBranchNode.THEN);
        connect(forNode, bodyStart);
        IBasicBlock bodyEnd = handleLoopBody(bodyStart, loopBody, continueNode, true, breakNode);
        jump(bodyEnd, continueNode, true);

        IASTExpression iter = cFor.getIterationExpression();
        IBasicBlock afterIter = handleExpression(continueNode, iter);
        connect(afterIter, condStart);

        IBranchNode loopEnd = new LabelNode(IBranchNode.ELSE);
        connect(forNode, loopEnd);
        jump(loopEnd, breakNode, false);

        prevNode = loopEnd;
        return prevNode;
    }

    private IBasicBlock handleLoopBody(IBasicBlock bodyStart, IASTStatement body, IConnectorNode continueNode, boolean continueBackward, IConnectorNode breakNode) {
        IConnectorNode savedContinue = outerContinueTarget;
        boolean savedContinuePos = outerContinueBackward;
        IConnectorNode savedBreak = outerBreakTarget;
        outerContinueTarget = continueNode;
        outerContinueBackward = continueBackward;
        outerBreakTarget = breakNode;
        IBasicBlock bodyEnd = expandGraph(bodyStart, body);
        outerContinueTarget = savedContinue;
        outerContinueBackward = savedContinuePos;
        outerBreakTarget = savedBreak;
        return bodyEnd;
    }

    private boolean connect(IBasicBlock prevNode, IBasicBlock postNode) {
        if (prevNode instanceof IExitNode || prevNode instanceof IJumpNode || prevNode == null) {
            unreachable.add(postNode);
            return false;
        }
        ((AbstractBasicBlock) prevNode).addOutgoing(postNode);
        ((AbstractBasicBlock) postNode).addIncoming(prevNode);
        return true;
    }

    private void jump(IBasicBlock prevNode, IConnectorNode phiNode, boolean isBackward) {
        if (prevNode instanceof IExitNode || prevNode instanceof IJumpNode || prevNode == null) {
            return;
        }
        GotoNode gotoNode = new GotoNode();
        connect(prevNode, gotoNode);
        gotoNode.setJump(phiNode, isBackward);
        ((PhiNode) phiNode).addIncoming(gotoNode);
    }

    private boolean fixPhi(IConnectorNode phiNode) {
        if (phiNode.getIncomingSize() == 0) {
            unreachable.add(phiNode);
            return false;
        }
        return true;
    }

    // strip the wrapping brackets
    private static IASTExpression unparenthesize(IASTExpression expression) {
        IASTExpression ret = expression;
        while (ret instanceof IASTUnaryExpression &&
                ((IASTUnaryExpression) ret).getOperator() == IASTUnaryExpression.op_bracketedPrimary)
            ret = ((IASTUnaryExpression) ret).getOperand();
        return ret;
    }

    // get the register which stores the evaluated value of expression
    private int getRegister(IASTExpression expression, boolean allocate) {
        if (expression instanceof IASTUnaryExpression) {
            int op = ((IASTUnaryExpression) expression).getOperator();
            if (op == IASTUnaryExpression.op_bracketedPrimary) {
                Messages.warn("CParser: brackets should have been unparenthesized for [%s]", expression.getRawSignature());
                return getRegister(unparenthesize(expression), allocate);
            }
            if (op == IASTUnaryExpression.op_postFixIncr || op == IASTUnaryExpression.op_postFixDecr) {
                // special case for postfix incr/decr, if fetch result, use previous value
                if (!allocate)
                    return getRegister(((IASTUnaryExpression) expression).getOperand(), allocate);
            }
        }
        if (expression instanceof IASTBinaryExpression) {
            int op = ((IASTBinaryExpression) expression).getOperator();
            if (op == IASTBinaryExpression.op_assign) {
                return getRegister(((IASTBinaryExpression) expression).getOperand1(), allocate);
            }
        }
        if (expression instanceof IASTExpressionList) {
            IASTExpression[] exprs = ((IASTExpressionList) expression).getExpressions();
            int len = exprs.length;
            if (len == 0)
                Messages.fatal("CParser: empty expression list [%s]", expression.getRawSignature());
            return getRegister(unparenthesize(exprs[len-1]), allocate);
        }
        int id = registers.indexOf(expression);
        if (id < 0) {
            if (allocate) {
                registers.add(expression);
                id = registers.indexOf(expression);
            } else
                Messages.fatal("CParser: uncomputed expression %s[%s]", expression.getClass().getSimpleName(), expression.getRawSignature());
        }
        return id;
    }

    // allocate a register to store the evaluated value of expression
    private int fetchRegister(IASTExpression expression) {
        return getRegister(expression, false);
    }

    private int createRegister(IASTExpression expression) {
        return getRegister(expression, true);
    }
}
