package com.xceptance.neodymium.ai.test;

import com.xceptance.neodymium.common.browser.Browser;
import com.xceptance.neodymium.common.testdata.DataFile;
import com.xceptance.neodymium.junit5.NeodymiumTest;

@DataFile("captcha.xml")
@Browser("Chrome_1600x800")
public class CaptchaTest extends AbstractAiTest
{

    @NeodymiumTest
    public void test() throws Exception
    {
        runAiTest();
    }
}
