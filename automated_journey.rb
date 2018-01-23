require "selenium-webdriver"

driver = Selenium::WebDriver.for :chrome

driver.get 'http://localhost:56000'

driver.switch_to.window( driver.window_handles.last )

driver.find_element(:id, 'eidasconnector_title').click
sleep(0.1)
driver.find_element(:id, 'eidasconnector_child').find_element(:css, "li:nth-child(2)").click

driver.find_element(:id, 'citizeneidas_title').click
sleep(0.1)
driver.find_element(:id, 'citizeneidas_child').find_element(:css, "li:nth-child(3)").click

dropDownMenu = driver.find_element(:id, 'eidasloa')
option = Selenium::WebDriver::Support::Select.new(dropDownMenu)
option.select_by(:value, 'http://eidas.europa.eu/LoA/substantial')

driver.find_element(:id, 'submit_tab2').click

driver.find_element(:id, 'submit_saml').click

driver.find_element(:css, 'input[type="submit"]').click

driver.find_element(:id, 'username').send_keys('stub-idp-demo')

driver.find_element(:id, 'password').send_keys('bar')

driver.find_element(:id, 'login').click

driver.find_element(:id, 'agree').click

driver.find_element(:css, 'input[type="submit"]').click

sleep(60)
