package zio.temporal.internal

import scala.reflect.ClassTag
import org.slf4j.LoggerFactory

private[zio] object StubProxies {
  private val logger = LoggerFactory.getLogger(getClass)

  final class IllegalStubProxyInvocationException(msg: String) extends RuntimeException(msg)

  def proxy[Delegate, Proxied](
    delegate: Delegate
  )(implicit
    delegateCtg: ClassTag[Delegate],
    proxiedCtg:  ClassTag[Proxied]
  ): Delegate with Proxied = {
    val Delegate = delegateCtg.runtimeClass
    val Proxied  = proxiedCtg.runtimeClass
    val classLoader = Option(Thread.currentThread().getContextClassLoader).getOrElse {
      getClass.getClassLoader
    }
    val proxy = java.lang.reflect.Proxy.newProxyInstance(
      classLoader,
      Array(Delegate, Proxied),
      (proxy, method, methodArgs) =>
        if (method.getDeclaringClass.isAssignableFrom(Delegate)) {
          logger.trace(s"Delegating $method call to delegate")
          method.invoke(delegate, methodArgs: _*)
        } else {
          logger.warn(s"Stub $method called, usually this shouldn't happen")
          throw new IllegalStubProxyInvocationException(
            s"$Proxied methods should not be invoked at runtime!\n" +
              s"It's likely that you forgot to wrap Workflow/Activity calls\n" +
              s"into ZWorkflowStub.execute/ZActivityStub.execute blocks, etc.\n" +
              s"Method was invoked: $method"
          )
        }
    )

    proxy.asInstanceOf[Delegate with Proxied]
  }
}
