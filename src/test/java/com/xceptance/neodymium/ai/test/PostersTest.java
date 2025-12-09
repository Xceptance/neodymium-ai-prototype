package com.xceptance.neodymium.ai.test;

import static com.codeborne.selenide.Condition.visible;

import com.codeborne.selenide.Selenide;
import com.xceptance.neodymium.common.browser.Browser;
import com.xceptance.neodymium.common.testdata.DataFile;
import com.xceptance.neodymium.junit5.NeodymiumTest;

@DataFile("posters.xml")
@Browser("Chrome_1600x800")
public class PostersTest extends AbstractAiTest
{

    @NeodymiumTest
    public void test() throws Exception
    {
        runAiTest();
    }

    /**
     * A method we force the AI to call
     */
    public void addToCart(String arg)
    {
        Selenide.$("#btn-add-to-cart").shouldBe(visible).click();
    }
}
