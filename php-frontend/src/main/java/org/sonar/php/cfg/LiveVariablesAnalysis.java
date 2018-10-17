/*
 * SonarQube PHP Plugin
 * Copyright (C) 2010-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.php.cfg;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.sonar.plugins.php.api.symbols.Symbol;
import org.sonar.plugins.php.api.symbols.SymbolTable;
import org.sonar.plugins.php.api.tree.Tree;
import org.sonar.plugins.php.api.tree.expression.ArrayInitializerTree;
import org.sonar.plugins.php.api.tree.expression.ArrayPairTree;
import org.sonar.plugins.php.api.tree.expression.AssignmentExpressionTree;
import org.sonar.plugins.php.api.tree.expression.ExpressionTree;
import org.sonar.plugins.php.api.tree.expression.FunctionCallTree;
import org.sonar.plugins.php.api.tree.statement.ExpressionStatementTree;
import org.sonar.plugins.php.api.visitors.PHPVisitorCheck;

public class LiveVariablesAnalysis {

  private final ControlFlowGraph controlFlowGraph;
  private final Map<CfgBlock, LiveVariables> liveVariablesPerBlock;

  private LiveVariablesAnalysis(ControlFlowGraph cfg, SymbolTable symbols) {
    controlFlowGraph = cfg;
    liveVariablesPerBlock = compute(controlFlowGraph, symbols);
  }

  public LiveVariables getLiveVariables(CfgBlock block) {
    return liveVariablesPerBlock.get(block);
  }

  public static LiveVariablesAnalysis analyze(ControlFlowGraph cfg, SymbolTable symbols) {
    return new LiveVariablesAnalysis(cfg, symbols);
  }

  private static Map<CfgBlock, LiveVariables> compute(ControlFlowGraph cfg, SymbolTable symbols) {
    Map<CfgBlock, LiveVariables> liveVariablesPerBlock = new HashMap<>();
    cfg.blocks().forEach(block -> {
      LiveVariables liveVariables = new LiveVariables(block, symbols);
      liveVariablesPerBlock.put(block, liveVariables);
    });

    Deque<CfgBlock> worklist = new ArrayDeque<>(cfg.blocks());
    while (!worklist.isEmpty()) {
      CfgBlock block = worklist.pop();
      LiveVariables liveVariables = liveVariablesPerBlock.get(block);
      boolean liveInHasChanged = liveVariables.propagate(liveVariablesPerBlock);
      if (liveInHasChanged) {
        block.predecessors().forEach(worklist::push);
      }
    }

    return liveVariablesPerBlock;
  }

  enum VariableState {
    WRITE,
    READ
  }

  static class LiveVariables {

    private final CfgBlock block;
    private final SymbolTable symbols;
    // 'gen' or 'use' - variables that are being read in the block
    private final Set<Symbol> gen = new HashSet<>();
    // 'kill' or 'def' - variables that are being written before being read in the block
    private final Set<Symbol> kill = new HashSet<>();
    // the 'in' and 'out' change during the algorithm
    private Set<Symbol> in = new HashSet<>();
    private Set<Symbol> out = new HashSet<>();

    LiveVariables(CfgBlock block, SymbolTable symbols) {
      this.block = block;
      this.symbols = symbols;
      initialize();
    }

    Set<Symbol> getIn() {
      // TODO immutable
      return in;
    }

    Set<Symbol> getOut() {
      // TODO immutable
      return out;
    }

    Set<Symbol> getGen() {
      return gen;
    }

    Set<Symbol> getKill() {
      return kill;
    }

    boolean propagate(Map<CfgBlock, LiveVariables> liveVariablesPerBlock) {
      // propagate the out values backwards, from successors
      out.clear();
      block.successors().stream().map(liveVariablesPerBlock::get).map(df -> df.in).forEach(out::addAll);
      // in = union + (out - kill)
      Set<Symbol> newIn = new HashSet<>(gen);
      newIn.addAll(Sets.difference(out, kill));
      boolean inHasChanged = !newIn.equals(in);
      in = newIn;
      return inHasChanged;
    }

    /**
     * TODO: The idea is to avoid false positives.
     * So for each element of the basic block, which can be any kind of Tree, I'll have a Visitor which will give a state for each symbols inside it - R, W or RW.
     * We only add to 'kill' if the Symbol inside the Expression is 'WRITE'
     * If its 'READ' or '{READ, WRITE}', we consider it as READ
     */
    private void initialize() {
      // process elements from bottom to top
      Set<Tree> assignmentLHS = new HashSet<>();
      for (Tree tree : Lists.reverse(block.elements())) {
        Tree element = tree;
        if (tree.is(Tree.Kind.EXPRESSION_STATEMENT)) {
          element = ((ExpressionStatementTree) tree).expression();
        }
        switch (element.getKind()) {
          case ASSIGNMENT:
            visitAssignment(assignmentLHS, (AssignmentExpressionTree) element);
            break;
          case VARIABLE_IDENTIFIER:
            addToGen(element);
            break;
          case VARIABLE_DECLARATION:
            visitVariableDeclaration(element);
            break;
          case FUNCTION_CALL:
            visitFunctionCall((FunctionCallTree) element);
            break;
          case ARRAY_INITIALIZER_FUNCTION:
          case ARRAY_INITIALIZER_BRACKET:
            visitArrayInitializer((ArrayInitializerTree) element);
            break;
          default:
            // ignore
        }
      }
    }

    private void visitArrayInitializer(ArrayInitializerTree arrayInitializer) {
      for (ArrayPairTree arrayPair : arrayInitializer.arrayPairs()) {
        if (arrayPair.key().is(Tree.Kind.VARIABLE_IDENTIFIER)) {
          addToGen(arrayPair.key());
        }
        if (arrayPair.value().is(Tree.Kind.VARIABLE_IDENTIFIER)) {
          addToGen(arrayPair.value());
        }
      }
    }

    private void visitFunctionCall(FunctionCallTree functionCallTree) {
      for (ExpressionTree arg : functionCallTree.arguments()) {
        if (arg.is(Tree.Kind.VARIABLE_IDENTIFIER)) {
          addToGen(arg);
        }
      }
    }

    private void visitVariableDeclaration(Tree element) {
      Symbol symbol;
      symbol = symbols.getSymbol(element);
      if (symbol != null) {
        // TODO is local?
        kill.add(symbol);
        gen.remove(symbol);
      }
    }

    private void visitAssignment(Set<Tree> assignmentLHS, AssignmentExpressionTree element) {
      ExpressionTree lhs = element.variable();
      Symbol symbol = null;
      if (lhs.is(Tree.Kind.VARIABLE_IDENTIFIER)) {
        symbol = symbols.getSymbol(lhs);
      }
      if (symbol != null) {
        assignmentLHS.add(lhs);
        kill.add(symbol);
        gen.remove(symbol);
      }
    }

    private void addToGen(Tree tree) {
      Symbol symbol = symbols.getSymbol(tree);
      if (symbol != null) {
        gen.add(symbol);
      }
    }
  }

  private static class TreeVisitor extends PHPVisitorCheck {

    static Map<Symbol, EnumSet<VariableState>> visit(Tree tree) {
      return null;
    }
  }
}
