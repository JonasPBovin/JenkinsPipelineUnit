package net.courtanet.jenkins

import groovy.grape.Grape

class Utils implements Serializable {

	def script

	Utils() {

	}

	Utils(script) {
		this.script = script
	}

	void ensureMath3() {
		Grape.grab(group:'org.apache.commons', module:'commons-math3', version:'3.6.1', classLoader: this.class.classLoader)
	}

	void parallelize(int count) {
		ensureMath3()
		if (!org.apache.commons.math3.primes.Primes.isPrime(count)) {
			echo "${count} was not prime"
		}
		// …
	}

	/**
	 * @return GIT config for devteam-tools
	 */
	def gitTools() {
	    return [branch: 'master']
	}

}