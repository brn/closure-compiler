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
 * @param {!Binder} binder
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
 * @param {!Binder} binder
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
 * @param {!Binder} binder
 */
Module.prototype.configure = function(binder) {
  if (!COMPILED) {
    binder.getA = function() {
      return 'a';
    };
  } else {
    binder.getA = function() {
      return 'b';
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

};

var module = new Module();
var binder = new Binder();
module.configure(binder);
var a = A.createInstance(binder);
var b = A.createInstance(binder);
document.getElementById('a').innerHTML = a.getStr() + b.getStr();