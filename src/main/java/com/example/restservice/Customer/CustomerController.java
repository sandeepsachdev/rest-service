package com.example.restservice.Customer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CustomerController {
    @Autowired
    private CustomerRepository customerRepo;

    @GetMapping("/customers")
    public List<Customer> listAll(Model model) {
        List<Customer> listCustomers = customerRepo.findAll();
        model.addAttribute("listCustomers", listCustomers);

        return listCustomers;
    }

    @GetMapping("/insert")
    public void insert(Model model) {

        Customer customer = new Customer();
        customer.setAge(10);
        customer.setName("Customer Name");
        customer.setEmail("email@email.com");
        customerRepo.save(customer);
    }

}