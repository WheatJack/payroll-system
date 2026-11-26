package com.insbean.payroll.item;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.item.dto.PayItemCreateReq;
import com.insbean.payroll.item.dto.PayItemDefDTO;
import com.insbean.payroll.item.dto.PayItemUpdateReq;
import com.insbean.payroll.security.CurrentUser;
import com.insbean.payroll.security.RequirePerm;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @RequirePerm("payroll:record:view")
    @GetMapping
    public Result<List<PayItemDefDTO>> list(@RequestParam(required = false) String category,
                                            @RequestParam(required = false) Integer status) {
        return Result.ok(itemService.list(CurrentUser.get().companyId(), category, status));
    }

    @RequirePerm("payroll:record:edit")
    @PostMapping
    public Result<PayItemDefDTO> create(@Valid @RequestBody PayItemCreateReq req) {
        return Result.ok(itemService.create(req, CurrentUser.get().companyId()));
    }

    @RequirePerm("payroll:record:edit")
    @PutMapping("/{id}")
    public Result<PayItemDefDTO> update(@PathVariable Long id, @Valid @RequestBody PayItemUpdateReq req) {
        return Result.ok(itemService.update(id, req));
    }
}
