package de.beuth.linkedin.scanner.impl

import java.net.URL


import de.beuth.proxybrowser.api.ProxyServer
import de.beuth.utils.UserAgentList
import org.openqa.selenium
import org.openqa.selenium.Proxy
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.phantomjs.{PhantomJSDriver, PhantomJSDriverService}
import org.openqa.selenium.remote.{CapabilityType, DesiredCapabilities, RemoteWebDriver}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Singleton Factory
  *
  * Creates different kinds of Selenium Webdrivers
  *
  * Slightly adjusted factory method pattern  (https://en.wikipedia.org/wiki/Factory_method_pattern)
  */
object WebDriverFactory {

  def getFirefoxDriver(proxy: Option[ProxyServer])(implicit ec: ExecutionContext): Future[FirefoxDriver] =
    Future {
      System.setProperty("webdriver.gecko.driver", "/opt/geckodriver")
      val cap = DesiredCapabilities.firefox()
      if(proxy.isDefined) cap.setCapability(CapabilityType.PROXY, prepareWebdirverProxy(proxy.get))
      cap.setCapability("webSecurityEnabled", false)
      cap.setCapability("browserTimeout", 20)
      cap.setCapability("timeout", 20)
      new FirefoxDriver(cap)
    }

  def getChromeRemoteDriver(proxy: Option[ProxyServer], remoteUrl: String = "http://localhost:4444/wd/hub")(implicit ec: ExecutionContext): Future[RemoteWebDriver] =
    Future {
      val cap = DesiredCapabilities.chrome()
      val chromeOptions = new ChromeOptions()
      /**
        * Headless is only working on Linux machines
        */
      //chromeOptions.addArguments("--headless")
      chromeOptions.addArguments("--no-sandbox")
      chromeOptions.addArguments("--disable-gpu")
      chromeOptions.addArguments("--disable-extensions");

      if (proxy.isDefined)
        cap.setCapability(CapabilityType.PROXY, prepareWebdirverProxy(proxy.get))

      cap.setCapability("webSecurityEnabled", false)
      cap.setCapability("browserTimeout", 20)
      cap.setCapability("timeout", 20)
      cap.setCapability(ChromeOptions.CAPABILITY, chromeOptions);
      new RemoteWebDriver(new URL(remoteUrl), cap)
    }

  def getChromeDriver(proxy: Option[ProxyServer])(implicit ec: ExecutionContext): Future[ChromeDriver] =
    Future {
      System.setProperty("webdriver.chrome.driver", "/opt/chromedriver")
      val cap = DesiredCapabilities.chrome()
      val chromeOptions = new ChromeOptions()
      /**
        * Headless is only working on Linux machines
        */
      //chromeOptions.addArguments("--headless")
      chromeOptions.addArguments("--no-sandbox")
      chromeOptions.addArguments("--disable-gpu")
      chromeOptions.addArguments("--disable-extensions");

      if(proxy.isDefined)
        cap.setCapability(CapabilityType.PROXY, prepareWebdirverProxy(proxy.get))

      cap.setCapability("webSecurityEnabled", false)
      cap.setCapability("browserTimeout", 20)
      cap.setCapability("timeout", 20)
      cap.setCapability(ChromeOptions.CAPABILITY, chromeOptions);
      new ChromeDriver(cap)
    }

  def getPhantomJsDriver(proxy: Option[ProxyServer])(implicit ec: ExecutionContext): Future[PhantomJSDriver] =
    Future {
      val cap = DesiredCapabilities.phantomjs()
      if(proxy.isDefined) cap.setCapability(CapabilityType.PROXY, prepareWebdirverProxy(proxy.get))
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_EXECUTABLE_PATH_PROPERTY, "/usr/local/Cellar/phantomjs/2.1.1/bin/phantomjs")
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX + "webSecurityEnabled", false)
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX + "javascriptEnabled", true)
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX + "userAgent", UserAgentList.getRnd())
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX + "XSSAuditingEnabled", true)
      //headers
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_CUSTOMHEADERS_PREFIX + "Accept-Language", "de-DE,de;q=0.8,en-US;q=0.6,en;q=0.4")
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_CUSTOMHEADERS_PREFIX + "Cache-Control", "no-cache")
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_CUSTOMHEADERS_PREFIX + "Pragma", "no-cache")
      //cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_CUSTOMHEADERS_PREFIX + "Host", "")
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_CUSTOMHEADERS_PREFIX + "upgrade-insecure-requests", "1")
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_CUSTOMHEADERS_PREFIX + "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_CUSTOMHEADERS_PREFIX + "Accept-Encoding", "gzip, deflate, br")
      cap.setCapability(PhantomJSDriverService.PHANTOMJS_PAGE_SETTINGS_PREFIX  + "takesScreenshot", false)
      cap.setJavascriptEnabled(true)
      new PhantomJSDriver(cap)
    }

  private def prepareWebdirverProxy(proxyServer: ProxyServer): Proxy =
    (new Proxy()).setHttpProxy(proxyServer.toString).setSslProxy(proxyServer.toString)

}
