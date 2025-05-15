package ai.core.example.service;

import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class UserInfoService {
    Map<String, UserInfo> users = Map.of(
            "1234567890", UserInfo.of("user-0001", "Stephen", "1234567890", "stephen@chancetop.com"),
            "0987654321", UserInfo.of("user-0002", "Celery", "0987654321", "celery@chancetop.com"));
    Map<String, Order> orders = Map.of(
            "user-0001", Order.of("order-0001", "202501010001", 100, "ACCEPTED", ZonedDateTime.parse("2025-01-01T08:01:00Z")),
            "user-0002", Order.of("order-0002", "202501010002", 200, "COMPLETED", ZonedDateTime.parse("2025-01-01T08:02:00Z")));

    @CoreAiMethod(name = "getUserLastOrder", description = "get user's last order by user_id.")
    public String getUserLastOrder(@CoreAiParameter(
            name = "userId",
            description = "the user id of the user",
            required = true) String userId) {
        return JSON.toJSON(orders.get(userId));
    }

    @CoreAiMethod(name = "getUserInfo", description = "get user's info by phone number.")
    public String getUserInfo(@CoreAiParameter(
            name = "phoneNumber",
            description = "the phone number of the user",
            required = true) String phoneNumber) {
        return JSON.toJSON(users.get(phoneNumber));
    }

    @CoreAiMethod(name = "createOrderIssue", description = "create order issue by order number and action.")
    public String createOrderIssue(
            @CoreAiParameter(
                    name = "orderNumber",
                    description = "the order number of the order",
                    required = true) String orderNumber,
            @CoreAiParameter(
                   name = "action",
                   description = "the action of the order issue",
                   required = true,
                   enums = {"REFUND", "WAIT", "DISCOUNT"}) String action) {
        return "Order issue created for order number: " + orderNumber + ", action: " + action;
    }

    public static class Order {
        public static Order of(String number, String orderNumber, Integer total, String status, ZonedDateTime orderTime) {
            var order = new Order();
            order.id = number;
            order.orderNumber = orderNumber;
            order.total = total;
            order.orderTime = orderTime;
            order.status = status;
            return order;
        }

        @Property(name = "id")
        public String id;

        @Property(name = "order_number")
        public String orderNumber;

        @Property(name = "total")
        public Integer total;

        @Property(name = "status")
        public String status;

        @Property(name = "order_time")
        public ZonedDateTime orderTime;
    }

    public static class UserInfo {
        public static UserInfo of(String number, String stephen, String number1, String mail) {
            var userInfo = new UserInfo();
            userInfo.id = number;
            userInfo.name = stephen;
            userInfo.phoneNumber = number1;
            userInfo.email = mail;
            return userInfo;
        }

        @Property(name = "id")
        public String id;

        @Property(name = "name")
        public String name;

        @Property(name = "phoneNumber")
        public String phoneNumber;

        @Property(name = "email")
        public String email;
    }
}
