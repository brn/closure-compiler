package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.base.Preconditions;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class AstBuilders {
  
  static abstract class AbstractAstBuilder {
    private boolean freeze = false;
    
    protected void checkModifiable() {
      Preconditions.checkArgument(freeze == false);
    }
    
    protected boolean isModifiable() {
      return this.freeze;
    }
    
    public final Node build() {
      Preconditions.checkArgument(this.freeze == false);
      this.freeze = true;
      return this.buildInternal(); 
    }
    
    protected abstract Node buildInternal();
  }
  
  static final class FunctionBuilder extends AbstractAstBuilder {
    private Node function;
    
    private Node paramList;
    
    private Node name;
    
    private Node block;
    
    public FunctionBuilder() {
      this.function = new Node(Token.FUNCTION);
      this.paramList = new Node(Token.PARAM_LIST);
      this.name = Node.newString(Token.NAME, "");
      this.block = new Node(Token.BLOCK);
    }
    
    public FunctionBuilder setParam(List<String> paramList) {
      checkModifiable();
      for (String paramName : paramList) {
        this.paramList.addChildToBack(Node.newString(Token.NAME, paramName));
      }
      return this;
    }
    
    public FunctionBuilder setName(String name) {
      checkModifiable();
      this.name.setString(name);
      return this;
    }
    
    public FunctionBuilder setBody(Node...statements) {
      checkModifiable();
      for (Node statement : statements) {
        block.addChildToBack(statement);
      }
      return this;
    }
    
    public Node buildInternal() {
      this.function.addChildToBack(this.name);
      this.function.addChildToBack(this.paramList);
      this.function.addChildToBack(this.block);
      return this.function;
    }
  }

  static final class CallBuilder extends AbstractAstBuilder  {
    private Node call;
    
    private Node target;
    
    private List<Node> parameters;
    
    public CallBuilder() {
      this(false);
    }
    
    public CallBuilder(boolean isNew) {
      int type = isNew? Token.NEW : Token.CALL;
      this.call = new Node(type);
    }
    
    public CallBuilder setCallTarget(String name) {
      checkModifiable();
      this.target = CampInjectionProcessor.newQualifiedNameNode(name);
      return this;
    }
    
    public CallBuilder setCallTarget(Node exp) {
      checkModifiable();
      this.target = exp;
      return this;
    }
    
    public CallBuilder setParamList(List<Node> parameters) {
      checkModifiable();
      this.parameters = parameters;
      return this;
    }
    
    public CallBuilder addParamListAll(List<Node> parameters) {
      for (Node param : parameters) {
        this.parameters.add(param);
      }
      return this;
    }
    
    public CallBuilder addParam(Node parameter) {
      checkModifiable();
      this.parameters.add(parameter);
      return this;
    }
    
    public Node buildInternal() {
      Preconditions.checkArgument(this.target != null, "CallBuilder target node is null.");
      this.call.addChildToBack(this.target);
      for (Node param : this.parameters) {
        this.call.addChildToBack(param);
      }
      return this.call;
    }
  }
}
