// file: vars/greetings.groovy
def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    echo "Hello"
    //sayHello {}
    //sayHello.hello(message: "World!")
}