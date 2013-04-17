/**
 * @constructor
 */
function X(){}
camp.injections.Injector.inject(X, 'abc');

X.prototype.abc = function(a,b,c){

};

X.prototype.abc = function(a,b,c,d) {

};
