var testNs = {foo:{bar:{}}};
var camp = {
      injections : {}
    };
var goog = {
      inherits : function(derived, base) {
        /**
         * @constructor
         */
        function Proxy(){}
        Proxy.prototype = base.prototype;
        derived.prototype = new Proxy;
        derived.prototype.constructor = base;
      }
    };

/**
 * @define {boolean}
 */
var COMPILED = false;

/**
 * @constructor
 * @param {string} a
 */
function A(a){
  this.string_ = a;
  this.b = null;
}
camp.injections.Injector.inject(A, 'setC');

/**
 * @return {string}
 */
A.prototype.getStr = function() {
  return this.string_ + this.b.getStr();
};


if (!COMPILED) {
  /**
   * @param {string} b
   */
  A.prototype.setB = function(b) {
    this.b = b;
  };
} else {
  /**
   * @param {string} b
   */
  A.prototype.setC = function(b) {
    this.b = b;
  };
}

/**
 * @constructor
 * @param {string} c
 */
function B(c) {
  this.b_ = c;
}

/**
 * @return {string}
 */
B.prototype.getStr = function() {
  return this.b_ + 'aaaa';
};

/**
 * @interface
 */
camp.injections.Module = function() {

};
camp.injections.Module.prototype.configure = function() {};

/**
 * @constructor
 * @implements {camp.injections.Module}
 */
function Module() {

}

Module.prototype.configure = function(binder) {
//  if (!COMPILED) {
    binder.bind('a').toInstance('a');
//  }
  binder.bind('b').to(B);
  binder.bind('c').toInstance('c');
};

camp.injections.modules.init([Module], function(injector) {
  var a = injector.getInstance(A);
  var b = injector.getInstance(A);
  document.getElementById('a').innerHTML = a.getStr() + b.getStr();
});
