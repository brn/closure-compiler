var testNs = {foo:{bar:{}}};
var camp = {
      injections : {}
    };

/**
 * @define {boolean}
 */
var COMPILED = false;

/**
 * @constructor
 */
function A(a){
  this.string_ = a;
  this.b = null;
}

/**
 * @returns {A}
 */
A.createInstance = function(binder) {
  var ret = new A(binder.getA());
  ret.setB && ret.setB(binder.getB());
  ret.setC && ret.setC(binder.getB());
  return ret;
};


/**
 * @returns {string}
 */
A.prototype.getStr = function() {
  return this.string_ + this.b.getStr();
};


if (!COMPILED) {
  A.prototype.setB = function(b) {
    this.b = b;
  };
} else {
  A.prototype.setC = function(b) {
    this.b = b;
  };
}

/**
 * @constructor
 */
function B(b) {
  this.b_ = b;
}

/**
 * @return {B}
 */
B.createInstance = function(binder) {
  return new B(binder.getA());
};

B.prototype.getStr = function() {
  return this.b_ + 'aaaa';
};

/**
 * @constructor
 */
function Module() {

}

/**
 * @constructor
 */
var Ext = function(){
      this['a'] = 'a';
      this['b'] = 'b';
    };

Ext.prototype.getString = function(name) {
  return this[name];
};

var ext = new Ext;

Module.prototype.configure = function(binder) {
  if (!COMPILED) {
    var a = ext.getString('a');
    binder.getA = function() {
      return a;
    };
  } else {
    var b = ext.getString('b');
    binder.getA = function() {
      return b;
    };
  }

  var instance;
  /**
   * @return {B}
   */
  binder.getB = function() {
    return instance = instance || B.createInstance(binder);
  };

  binder.interceptor0 = function() {
  };
};

/**
 * @constructor
 */
function Binder(){

}

var bindings = new Binder();

var module = new Module();
module.configure(bindings);
var a = A.createInstance(bindings);
var b = A.createInstance(bindings);
document.getElementById('a').innerHTML = a.getStr() + b.getStr();