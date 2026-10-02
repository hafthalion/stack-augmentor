package com.hafnium.examples.live

/**
 * Three requests whose stack traces the shop logs. With the agent's live-stack mode (`run`), every frame of them
 * shows its ids; with the agent instrumenting classes (`runInstrumented`), only the frames the exception left:
 *
 * 1. The payment is declined, and `Checkout.pay` catches the exception: `pay` and the frames below it.
 * 2. A slow payment is logged with an exception that is never thrown: every frame.
 * 3. A receipt fails in the superclass constructor: the `Receipt` constructor that called it, and `Shop.handle`,
 *    which catches the exception.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val shop = Shop("web-shop", PaymentClient("https://pay.example.com/eu"))
        shop.handle("alice", "/checkout/4711")
        shop.handle("bob", "/checkout/4712")
        shop.handle("carol", "/receipt/4713")
    }
}
