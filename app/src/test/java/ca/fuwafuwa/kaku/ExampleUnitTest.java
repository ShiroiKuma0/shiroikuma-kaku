package ca.fuwafuwa.kaku;

import org.junit.Test;

/**
 * To work on unit tests, switch the Test Artifact in the Build Variants view.
 */
public class ExampleUnitTest {

    @Test
    public void TestCircledNum(){
        for (int i = 1; i <= 100; i++){
            System.out.println(LangUtils.Companion.ConvertIntToCircledNum(i));
        }
    }
}
